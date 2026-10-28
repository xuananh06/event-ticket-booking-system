package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Booking;
import com.eventbooking.model.BookingItem;
import com.eventbooking.model.Event;
import com.eventbooking.service.BookingRules;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.eventbooking.dao.JdbcAccess.*;

/** Connection-accepting booking persistence; transactions belong to the service. */
public class BookingDao {
    public record Draft(long id, LocalDateTime createdAt, LocalDateTime expiresAt) { }

    private static final String SALE = "e.status='PUBLISHED' AND e.starts_at>SYSUTCDATETIME() AND e.ends_at>e.starts_at "
            + "AND u.role='ORGANIZER' AND u.is_active=1 AND category.is_active=1 AND venue.is_active=1";

    /** Locks live sale references; the service also owns the shared management lock. */
    public Event saleEvent(Connection c, long event) throws SQLException {
        if (scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events e WITH (UPDLOCK,HOLDLOCK) "
                + "JOIN dbo.users u WITH (UPDLOCK,HOLDLOCK) ON u.id=e.organizer_id "
                + "JOIN dbo.categories category WITH (UPDLOCK,HOLDLOCK) ON category.id=e.category_id "
                + "JOIN dbo.venues venue WITH (UPDLOCK,HOLDLOCK) ON venue.id=e.venue_id WHERE e.id=? AND " + SALE, event) != 1) {
            throw DomainException.conflict("This event is not available for reservations. Reopen event browsing.");
        }
        var events = new EventDao();
        Event result = events.published(c, event, events.now(c));
        BookingRules.requireDetails(result);
        return result;
    }

    public Draft create(Connection c, long customer, long event, String code) throws SQLException {
        try (PreparedStatement s = statement(c, "DECLARE @created datetime2(3)=SYSUTCDATETIME(); "
                + "INSERT dbo.bookings(booking_code,customer_id,event_id,status,total_amount,created_at,expires_at) "
                + "OUTPUT INSERTED.id,INSERTED.created_at,INSERTED.expires_at "
                + "VALUES (?,?,?,'PENDING',0,@created,DATEADD(MINUTE,5,@created))", code, customer, event);
             ResultSet r = s.executeQuery()) {
            if (!r.next()) { throw new SQLException("Expected booking identity."); }
            return new Draft(r.getLong(1), r.getObject(2, LocalDateTime.class), r.getObject(3, LocalDateTime.class));
        }
    }

    /** Guarded replacement never reads or locks the previous owner's booking. */
    public BookingItem claim(Connection c, long customer, long event, Draft booking, long inventory) throws SQLException {
        try (PreparedStatement s = statement(c, "UPDATE es SET state='HELD',current_booking_id=b.id,hold_expires_at=b.expires_at "
                + "OUTPUT INSERTED.id,INSERTED.price,s.row_label,s.seat_number "
                + "FROM dbo.event_seats es JOIN dbo.seats s ON s.id=es.seat_id AND s.venue_id=es.venue_id AND s.is_active=1 "
                + "JOIN dbo.events e ON e.id=es.event_id AND e.venue_id=es.venue_id "
                + "JOIN dbo.users u ON u.id=e.organizer_id JOIN dbo.categories category ON category.id=e.category_id "
                + "JOIN dbo.venues venue ON venue.id=e.venue_id "
                + "JOIN dbo.bookings b ON b.id=? AND b.event_id=e.id AND b.customer_id=? AND b.status='PENDING' "
                + "JOIN dbo.users customer ON customer.id=b.customer_id AND customer.role='CUSTOMER' AND customer.is_active=1 "
                + "WHERE es.id=? AND es.event_id=? AND b.expires_at=? AND b.expires_at>SYSUTCDATETIME() AND " + SALE
                + " AND (es.state='AVAILABLE' OR (es.state='HELD' AND es.hold_expires_at<=SYSUTCDATETIME()))",
                booking.id(), customer, inventory, event, booking.expiresAt()); ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                throw DomainException.conflict("One or more selected seats are no longer available. Review your selection and retry.");
            }
            BookingItem result = new BookingItem(r.getLong(1), r.getBigDecimal(2), BookingRules.seatLabel(r.getNString(3), r.getInt(4)));
            if (r.next()) { throw new SQLException("Expected one inventory claim."); }
            return result;
        }
    }

    public void finish(Connection c, long customer, long event, Draft booking, BigDecimal total) throws SQLException {
        try (PreparedStatement s = statement(c, "UPDATE b SET total_amount=? FROM dbo.bookings b "
                + "JOIN dbo.users customer ON customer.id=b.customer_id AND customer.role='CUSTOMER' AND customer.is_active=1 "
                + "JOIN dbo.events e ON e.id=b.event_id JOIN dbo.users u ON u.id=e.organizer_id "
                + "JOIN dbo.categories category ON category.id=e.category_id JOIN dbo.venues venue ON venue.id=e.venue_id "
                + "WHERE b.id=? AND b.customer_id=? AND b.event_id=? AND b.status='PENDING' AND b.expires_at=? "
                + "AND b.expires_at>SYSUTCDATETIME() AND " + SALE,
                total, booking.id(), customer, event, booking.expiresAt())) {
            if (s.executeUpdate() != 1) {
                throw DomainException.conflict("The reservation deadline or event eligibility changed. No seats were reserved; please retry.");
            }
        }
    }

    public Booking owned(Connection c, long customer, long id, boolean lock) throws SQLException {
        String hint = lock ? " WITH (UPDLOCK,HOLDLOCK)" : "";
        try (PreparedStatement s = statement(c, "SELECT b.id,b.booking_code,b.event_id,e.title,b.status,b.total_amount,b.created_at,b.expires_at "
                + "FROM dbo.bookings b" + hint + " JOIN dbo.events e ON e.id=b.event_id WHERE b.id=? AND b.customer_id=?", id, customer);
             ResultSet r = s.executeQuery()) {
            if (!r.next()) { throw DomainException.missing(); }
            return new Booking(r.getLong("id"), r.getString("booking_code"), r.getLong("event_id"), r.getNString("title"),
                    r.getString("status"), r.getBigDecimal("total_amount"), r.getObject("created_at", LocalDateTime.class), r.getObject("expires_at", LocalDateTime.class));
        }
    }

    public void addItem(Connection c, long booking, long event, BookingItem item) throws SQLException {
        update(c, "INSERT dbo.booking_items(booking_id,event_id,event_seat_id,unit_price,seat_label_snapshot) VALUES (?,?,?,?,?)",
                booking, event, item.eventSeatId(), item.unitPrice(), item.seatLabel());
    }

    public void expire(Connection c, long customer, long booking) throws SQLException {
        try (PreparedStatement s = statement(c, "UPDATE dbo.bookings SET status='EXPIRED' WHERE id=? AND customer_id=? "
                + "AND status='PENDING' AND expires_at<=SYSUTCDATETIME()", booking, customer)) {
            if (s.executeUpdate() != 1) {
                throw DomainException.conflict("Only an expired pending reservation can be released.");
            }
        }
    }

    /** Booking lock precedes inventory locks; historical item IDs never authorize release. */
    public void releaseExpiredInventory(Connection c, Booking booking) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement s = statement(c, "SELECT id FROM dbo.event_seats WITH (UPDLOCK,HOLDLOCK) "
                + "WHERE current_booking_id=? AND event_id=? ORDER BY id", booking.id(), booking.eventId()); ResultSet r = s.executeQuery()) {
            while (r.next()) { ids.add(r.getLong(1)); }
        }
        for (long id : ids) {
            try (PreparedStatement s = statement(c, "UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL "
                    + "WHERE id=? AND event_id=? AND current_booking_id=? AND state='HELD' AND hold_expires_at<=SYSUTCDATETIME()",
                    id, booking.eventId(), booking.id())) {
                s.executeUpdate();
            }
        }
    }

    /** Caller has already obtained the owner-bound header on this connection. */
    public List<BookingItem> items(Connection c, long booking) throws SQLException {
        List<BookingItem> items = new ArrayList<>();
        try (PreparedStatement s = statement(c, "SELECT event_seat_id,unit_price,seat_label_snapshot FROM dbo.booking_items WHERE booking_id=? ORDER BY event_seat_id", booking);
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                items.add(new BookingItem(r.getLong(1), r.getBigDecimal(2), r.getNString(3)));
            }
        }
        return List.copyOf(items);
    }
}
