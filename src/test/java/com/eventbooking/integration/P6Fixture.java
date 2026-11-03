package com.eventbooking.integration;

import com.eventbooking.dao.BookingDao;
import com.eventbooking.dao.PaymentDao;
import com.eventbooking.model.Booking;
import com.eventbooking.model.BookingItem;
import com.eventbooking.service.PaymentService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses exact P5 ownership/cleanup and compares every owned transactional row. */
final class P6Fixture implements AutoCloseable {
    final P5Fixture base;
    final P3Fixture f;
    final BookingDao bookings;
    final PaymentDao payments = new PaymentDao();
    final long customer, event;
    final List<Long> ids;

    P6Fixture(int seats) throws Exception {
        base = new P5Fixture(seats);
        f = base.f;
        bookings = base.dao;
        customer = base.customer;
        event = base.event;
        ids = base.ids;
    }

    static String key() { return UUID.randomUUID().toString(); }

    long hold(int... indexes) { return base.service().hold(customer, event, base.selection(indexes)); }

    PaymentService service() { return service(payments); }

    PaymentService service(PaymentDao dao) { return new PaymentService(f.access, bookings, dao); }

    Booking booking(long id) { return f.access.read(c -> bookings.owned(c, customer, id, false)); }

    List<BookingItem> items(long id) { return f.access.read(c -> bookings.items(c, id)); }

    LocalDateTime deadline(long id, int secondsFromNow) { return base.deadline(id, secondsFromNow); }

    long attempts(long booking) {
        return f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=?", booking));
    }

    long successes(long booking) {
        return f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=? AND succeeded_at IS NOT NULL", booking));
    }

    void assertHeld(long booking, int expected) {
        Booking header = booking(booking);
        assertEquals("PENDING", header.status());
        assertEquals(expected, f.access.read(c -> scalar(c,
                "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND current_booking_id=? "
                        + "AND state='HELD' AND hold_expires_at=?", event, booking, header.expiresAt())).longValue());
    }

    void assertConfirmed(long booking, int expected) {
        Booking header = booking(booking);
        assertEquals("CONFIRMED", header.status());
        assertNotNull(header.confirmedAt());
        assertNull(header.cancelledAt());
        assertEquals(expected, items(booking).size());
        assertEquals(expected, f.access.read(c -> scalar(c,
                "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND current_booking_id=? "
                        + "AND state='SOLD' AND hold_expires_at IS NULL", event, booking)).longValue());
        assertEquals(1, successes(booking));
        assertEquals(1, f.access.read(c -> scalar(c,
                "SELECT COUNT_BIG(*) FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id "
                        + "WHERE b.id=? AND p.status='SUCCEEDED' AND p.amount=b.total_amount "
                        + "AND p.created_at=p.succeeded_at AND p.succeeded_at=b.confirmed_at "
                        + "AND p.refunded_at IS NULL", booking)).longValue());
    }

    Map<String, String> snapshot() {
        return f.access.read(c -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("bookings", digest(c, "SELECT b.* FROM dbo.bookings b WHERE b.event_id=? ORDER BY b.id"));
            values.put("items", digest(c, "SELECT bi.* FROM dbo.booking_items bi WHERE bi.event_id=? ORDER BY bi.id"));
            values.put("payments", digest(c, "SELECT p.* FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id WHERE b.event_id=? ORDER BY p.id"));
            values.put("inventory", digest(c, "SELECT es.* FROM dbo.event_seats es WHERE es.event_id=? ORDER BY es.id"));
            return Map.copyOf(values);
        });
    }

    private String digest(Connection c, String sql) throws SQLException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (var s = statement(c, sql, event); var rows = s.executeQuery()) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    count++;
                    for (int i = 1; i <= columns; i++) {
                        Object value = rows.getObject(i);
                        String text = value == null ? "null:" : value.getClass().getName() + ":" + value;
                        digest.update((text.length() + ":" + text + "|").getBytes(StandardCharsets.UTF_8));
                    }
                    digest.update((byte) '\n');
                }
            }
            return count + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public void close() throws Exception { base.close(); }
}
