package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Booking;
import com.eventbooking.model.BookingItem;
import com.eventbooking.model.Payment;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.eventbooking.dao.JdbcAccess.*;

/** Financial persistence on the caller's connection; never owns a transaction. */
public class PaymentDao {
    private static final String FIELDS = "id,booking_id,idempotency_key,request_fingerprint,amount,status,created_at,succeeded_at,refunded_at";
    private static final String INSERTED_FIELDS = "INSERTED.id,INSERTED.booking_id,INSERTED.idempotency_key,INSERTED.request_fingerprint,"
            + "INSERTED.amount,INSERTED.status,INSERTED.created_at,INSERTED.succeeded_at,INSERTED.refunded_at";
    private static final String LIVE = "b.status='PENDING' AND b.expires_at>SYSUTCDATETIME() AND e.starts_at>SYSUTCDATETIME() "
            + "AND e.ends_at>e.starts_at AND e.status='PUBLISHED' AND u.role='ORGANIZER' AND u.is_active=1 "
            + "AND category.is_active=1 AND venue.is_active=1 AND customer.role='CUSTOMER' AND customer.is_active=1";
    private static final String SALE_JOIN = " FROM dbo.bookings b JOIN dbo.events e ON e.id=b.event_id "
            + "JOIN dbo.users u ON u.id=e.organizer_id JOIN dbo.categories category ON category.id=e.category_id "
            + "JOIN dbo.venues venue ON venue.id=e.venue_id JOIN dbo.users customer ON customer.id=b.customer_id";

    public LocalDateTime millisNow(Connection c) throws SQLException {
        try (var s = statement(c, "SELECT CONVERT(datetime2(3),SYSUTCDATETIME())"); var r = s.executeQuery()) {
            r.next(); return r.getObject(1, LocalDateTime.class);
        }
    }

    public Optional<Payment> find(Connection c, long booking, String key) throws SQLException {
        try (var s = statement(c, "SELECT " + FIELDS + " FROM dbo.payments WITH (UPDLOCK,HOLDLOCK) WHERE booking_id=? AND idempotency_key=?", booking, key); var r = s.executeQuery()) {
            return r.next() ? Optional.of(map(r)) : Optional.empty();
        }
    }

    public Optional<Payment> successful(Connection c, long booking) throws SQLException {
        try (var s = statement(c, "SELECT " + FIELDS + " FROM dbo.payments WITH (UPDLOCK,HOLDLOCK) WHERE booking_id=? AND succeeded_at IS NOT NULL ORDER BY id", booking); var r = s.executeQuery()) {
            if (!r.next()) { return Optional.empty(); }
            Payment payment = map(r);
            if (r.next()) { throw inconsistent(); }
            return Optional.of(payment);
        }
    }

    public com.eventbooking.model.Page<Payment> page(Connection c, long booking, int number, int size) throws SQLException {
        long count = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=?", booking);
        List<Payment> rows = new ArrayList<>();
        try (var s = statement(c, "SELECT " + FIELDS + " FROM dbo.payments WHERE booking_id=? ORDER BY created_at DESC,id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                booking, com.eventbooking.model.Page.offset(number, size), size); var r = s.executeQuery()) {
            while (r.next()) { rows.add(map(r)); }
        }
        return new com.eventbooking.model.Page<>(rows, count, number, size);
    }

    public Optional<Payment> attempt(Connection c, long booking, long id) throws SQLException {
        try (var s = statement(c, "SELECT " + FIELDS + " FROM dbo.payments WHERE booking_id=? AND id=?", booking, id); var r = s.executeQuery()) {
            return r.next() ? Optional.of(map(r)) : Optional.empty();
        }
    }

    public void lockAllocation(Connection c, Booking booking, List<BookingItem> items, String state, boolean physical) throws SQLException {
        List<Long> owned = new ArrayList<>();
        try (var s = statement(c, "SELECT id FROM dbo.event_seats WITH (HOLDLOCK) WHERE current_booking_id=? ORDER BY id", booking.id()); var r = s.executeQuery()) {
            while (r.next()) { owned.add(r.getLong(1)); }
        }
        List<Long> expected = items.stream().map(BookingItem::eventSeatId).sorted().toList();
        if (!owned.equals(expected) || scalar(c, "SELECT COUNT_BIG(*) FROM dbo.booking_items WHERE booking_id=? AND event_id<>?", booking.id(), booking.eventId()) != 0) {
            throw inconsistent();
        }
        for (long id : expected) { lockSeat(c, booking, id, state, physical); }
    }

    public void lockSeat(Connection c, Booking booking, long inventory, String state, boolean physical) throws SQLException {
        try (var s = statement(c, "SELECT es.event_id,es.current_booking_id,es.state,es.hold_expires_at,s.is_active, "
                + "CASE WHEN es.venue_id=e.venue_id AND s.venue_id=e.venue_id THEN 1 ELSE 0 END AS membership "
                + "FROM dbo.event_seats es WITH (UPDLOCK,HOLDLOCK) JOIN dbo.seats s ON s.id=es.seat_id "
                + "JOIN dbo.events e ON e.id=es.event_id WHERE es.id=?", inventory); var r = s.executeQuery()) {
            if (!r.next() || r.getLong("event_id") != booking.eventId() || r.getLong("current_booking_id") != booking.id()
                    || !state.equals(r.getString("state")) || !r.getBoolean("membership") || (physical && !r.getBoolean("is_active"))) {
                throw inconsistent();
            }
            LocalDateTime expires = r.getObject("hold_expires_at", LocalDateTime.class);
            if (("HELD".equals(state) && !booking.expiresAt().equals(expires)) || ("SOLD".equals(state) && expires != null)) { throw inconsistent(); }
        }
    }

    public void verifyLive(Connection c, Booking booking) throws SQLException {
        if (scalar(c, "SELECT COUNT_BIG(*)" + SALE_JOIN + " WHERE b.id=? AND b.total_amount=? AND " + LIVE, booking.id(), booking.total()) != 1) {
            throw DomainException.conflict("The reservation expired or the event is no longer eligible. No new payment was recorded.");
        }
    }

    public Payment record(Connection c, Booking booking, String key, String fingerprint, String outcome, LocalDateTime at) throws SQLException {
        boolean success = outcome.equals("success");
        try (var s = statement(c, "INSERT dbo.payments(booking_id,idempotency_key,request_fingerprint,amount,status,created_at,succeeded_at) "
                + "OUTPUT " + INSERTED_FIELDS
                + " SELECT b.id,?,?,?,?,?,?" + SALE_JOIN + " WHERE b.id=? AND b.total_amount=? AND b.created_at<=? AND b.expires_at>? AND " + LIVE,
                key, fingerprint, booking.total(), success ? "SUCCEEDED" : "FAILED", at, success ? at : null,
                booking.id(), booking.total(), at, at); var r = s.executeQuery()) {
            if (!r.next()) { throw DomainException.conflict("The reservation expired or changed. No payment was recorded."); }
            return map(r);
        }
    }

    public void sell(Connection c, Booking booking, long inventory) throws SQLException {
        try (var s = statement(c, "UPDATE es SET state='SOLD',hold_expires_at=NULL FROM dbo.event_seats es "
                + "JOIN dbo.bookings b ON b.id=es.current_booking_id AND b.event_id=es.event_id JOIN dbo.events e ON e.id=b.event_id "
                + "WHERE es.id=? AND es.current_booking_id=? AND es.event_id=? AND es.state='HELD' AND es.hold_expires_at=? "
                + "AND b.status='PENDING' AND b.expires_at>SYSUTCDATETIME() AND e.starts_at>SYSUTCDATETIME()",
                inventory, booking.id(), booking.eventId(), booking.expiresAt())) {
            if (s.executeUpdate() != 1) { throw inconsistent(); }
        }
    }

    public void confirm(Connection c, Booking booking, LocalDateTime at) throws SQLException {
        try (var s = statement(c, "UPDATE b SET status='CONFIRMED',confirmed_at=?" + SALE_JOIN
                + " WHERE b.id=? AND b.total_amount=? AND b.created_at<=? AND " + LIVE
                + " AND EXISTS (SELECT 1 FROM dbo.payments p WHERE p.booking_id=b.id AND p.status='SUCCEEDED' AND p.amount=b.total_amount) "
                + "AND NOT EXISTS (SELECT 1 FROM dbo.event_seats es WHERE es.current_booking_id=b.id AND (es.state<>'SOLD' OR es.hold_expires_at IS NOT NULL))",
                at, booking.id(), booking.total(), at)) {
            if (s.executeUpdate() != 1) { throw inconsistent(); }
        }
    }

    public void lockCancellationEvent(Connection c, Booking booking) throws SQLException {
        if (scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events WITH (UPDLOCK,HOLDLOCK) WHERE id=?", booking.eventId()) != 1) { throw inconsistent(); }
    }

    /** Cancellation is not a new sale: inactive references and past hold expiry on CONFIRMED are irrelevant. */
    public void verifyCancellation(Connection c, Booking booking) throws SQLException {
        if (scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings b JOIN dbo.events e ON e.id=b.event_id "
                + "WHERE b.id=? AND b.status=? AND e.starts_at>SYSUTCDATETIME() "
                + "AND (b.status='CONFIRMED' OR (b.status='PENDING' AND b.expires_at>SYSUTCDATETIME()))", booking.id(), booking.status()) != 1) {
            throw DomainException.conflict("Only a live pending or confirmed reservation before event start can be cancelled.");
        }
    }

    public void refund(Connection c, Booking booking, Payment payment, LocalDateTime at) throws SQLException {
        try (var s = statement(c, "UPDATE p SET status='REFUNDED',refunded_at=? FROM dbo.payments p "
                + "JOIN dbo.bookings b ON b.id=p.booking_id JOIN dbo.events e ON e.id=b.event_id "
                + "WHERE p.id=? AND p.booking_id=? AND p.status='SUCCEEDED' AND p.amount=? AND p.succeeded_at<=? "
                + "AND p.refunded_at IS NULL AND b.status='CONFIRMED' AND e.starts_at>SYSUTCDATETIME()",
                at, payment.id(), booking.id(), booking.total(), at)) {
            if (s.executeUpdate() != 1) { throw inconsistent(); }
        }
    }

    public void release(Connection c, Booking booking, long inventory, String state) throws SQLException {
        String allocation = state.equals("HELD") ? " AND es.hold_expires_at=? AND b.expires_at>SYSUTCDATETIME()" : " AND es.hold_expires_at IS NULL";
        List<Object> values = new ArrayList<>(List.of(inventory, booking.id(), booking.eventId(), state, booking.status()));
        if (state.equals("HELD")) { values.add(booking.expiresAt()); }
        try (var s = statement(c, "UPDATE es SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL FROM dbo.event_seats es "
                + "JOIN dbo.bookings b ON b.id=es.current_booking_id AND b.event_id=es.event_id JOIN dbo.events e ON e.id=b.event_id "
                + "WHERE es.id=? AND es.current_booking_id=? AND es.event_id=? AND es.state=? AND b.status=? "
                + "AND e.starts_at>SYSUTCDATETIME()" + allocation, values.toArray())) {
            if (s.executeUpdate() != 1) { throw inconsistent(); }
        }
    }

    public void cancel(Connection c, Booking booking, LocalDateTime at) throws SQLException {
        String financial = booking.status().equals("CONFIRMED")
                ? " AND EXISTS(SELECT 1 FROM dbo.payments p WHERE p.booking_id=b.id AND p.status='REFUNDED' AND p.amount=b.total_amount AND p.refunded_at=?)"
                : " AND NOT EXISTS(SELECT 1 FROM dbo.payments p WHERE p.booking_id=b.id AND p.succeeded_at IS NOT NULL)";
        List<Object> values = new ArrayList<>(List.of(at, booking.id(), booking.status(), at, at));
        if (booking.status().equals("CONFIRMED")) { values.add(at); }
        try (var s = statement(c, "UPDATE b SET status='CANCELLED',cancelled_at=? FROM dbo.bookings b JOIN dbo.events e ON e.id=b.event_id "
                + "WHERE b.id=? AND b.status=? AND b.created_at<=? AND (b.confirmed_at IS NULL OR b.confirmed_at<=?) "
                + "AND e.starts_at>SYSUTCDATETIME() AND (b.status='CONFIRMED' OR b.expires_at>SYSUTCDATETIME()) "
                + "AND NOT EXISTS(SELECT 1 FROM dbo.event_seats es WHERE es.current_booking_id=b.id)" + financial, values.toArray())) {
            if (s.executeUpdate() != 1) { throw inconsistent(); }
        }
    }

    private Payment map(ResultSet r) throws SQLException {
        return new Payment(r.getLong("id"), r.getLong("booking_id"), r.getString("idempotency_key"), r.getString("request_fingerprint"),
                r.getBigDecimal("amount"), r.getString("status"), r.getObject("created_at", LocalDateTime.class),
                r.getObject("succeeded_at", LocalDateTime.class), r.getObject("refunded_at", LocalDateTime.class));
    }

    private static DomainException inconsistent() { return DomainException.conflict("The reservation allocation or financial state changed. No partial action was saved."); }
}
