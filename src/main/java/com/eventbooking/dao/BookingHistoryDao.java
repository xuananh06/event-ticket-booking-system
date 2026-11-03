package com.eventbooking.dao;

import com.eventbooking.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.eventbooking.dao.JdbcAccess.*;

/** Read projections never normalize expired reservations or use public catalog visibility. */
public final class BookingHistoryDao {
    public Page<BookingHistory> list(Connection c, long customer, String status, LocalDateTime now, int page, int size) throws SQLException {
        List<Object> values = new ArrayList<>();
        values.add(customer);
        String where = " WHERE b.customer_id=?";
        if (!status.isEmpty()) {
            where += " AND CASE WHEN b.status='PENDING' AND b.expires_at<=? THEN 'EXPIRED' ELSE b.status END=?";
            values.add(now); values.add(status);
        }
        long total = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings b" + where, values.toArray());
        values.add(Page.offset(page, size)); values.add(size);
        List<BookingHistory> rows = new ArrayList<>();
        try (var s = statement(c, "SELECT b.id,b.booking_code,b.event_id,e.title,b.status,b.total_amount,b.created_at,b.expires_at,b.confirmed_at,b.cancelled_at,"
                + "(SELECT COUNT(*) FROM dbo.booking_items bi WHERE bi.booking_id=b.id) AS quantity "
                + "FROM dbo.bookings b JOIN dbo.events e ON e.id=b.event_id" + where
                + " ORDER BY b.created_at DESC,b.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", values.toArray()); var r = s.executeQuery()) {
            while (r.next()) {
                Booking booking = new Booking(r.getLong("id"), r.getString("booking_code"), r.getLong("event_id"), r.getNString("title"),
                        r.getString("status"), r.getBigDecimal("total_amount"), r.getObject("created_at", LocalDateTime.class),
                        r.getObject("expires_at", LocalDateTime.class), r.getObject("confirmed_at", LocalDateTime.class), r.getObject("cancelled_at", LocalDateTime.class));
                rows.add(new BookingHistory(booking, r.getInt("quantity"), now));
            }
        }
        return new Page<>(rows, total, page, size);
    }

    /** A read-only offer; the write use case independently validates every invariant again. */
    public boolean canPay(Connection c, Booking b, LocalDateTime now) throws SQLException {
        return scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings b JOIN dbo.events e ON e.id=b.event_id "
                + "JOIN dbo.users u ON u.id=e.organizer_id JOIN dbo.categories category ON category.id=e.category_id "
                + "JOIN dbo.venues venue ON venue.id=e.venue_id WHERE b.id=? AND b.status='PENDING' AND b.expires_at>? "
                + "AND e.starts_at>? AND e.ends_at>e.starts_at AND e.status='PUBLISHED' AND u.role='ORGANIZER' AND u.is_active=1 "
                + "AND category.is_active=1 AND venue.is_active=1 AND NOT EXISTS(SELECT 1 FROM dbo.payments p WHERE p.booking_id=b.id AND p.succeeded_at IS NOT NULL) "
                + "AND (SELECT COUNT_BIG(*) FROM dbo.booking_items bi WHERE bi.booking_id=b.id) BETWEEN 1 AND 50 "
                + "AND (SELECT SUM(bi.unit_price) FROM dbo.booking_items bi WHERE bi.booking_id=b.id)=b.total_amount "
                + "AND (SELECT COUNT_BIG(*) FROM dbo.event_seats es WHERE es.current_booking_id=b.id)=(SELECT COUNT_BIG(*) FROM dbo.booking_items bi WHERE bi.booking_id=b.id) "
                + "AND NOT EXISTS(SELECT 1 FROM dbo.booking_items bi LEFT JOIN dbo.event_seats es ON es.id=bi.event_seat_id "
                + "LEFT JOIN dbo.seats seat ON seat.id=es.seat_id WHERE bi.booking_id=b.id AND (es.id IS NULL OR es.current_booking_id IS NULL "
                + "OR es.current_booking_id<>b.id OR es.event_id<>b.event_id OR bi.event_id<>b.event_id OR es.state<>'HELD' "
                + "OR es.hold_expires_at<>b.expires_at OR es.hold_expires_at IS NULL OR seat.is_active<>1 OR seat.venue_id<>e.venue_id OR es.venue_id<>e.venue_id))",
                b.id(), now, now) == 1;
    }

    public boolean canCancel(Connection c, Booking booking, LocalDateTime now) throws SQLException {
        return scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings b JOIN dbo.events e ON e.id=b.event_id WHERE b.id=? AND e.starts_at>? "
                + "AND (b.status='CONFIRMED' OR (b.status='PENDING' AND b.expires_at>?))", booking.id(), now, now) == 1;
    }
}
