package com.eventbooking.integration;

import com.eventbooking.dao.BookingDao;
import com.eventbooking.service.BookingService;

import java.util.List;

import static com.eventbooking.dao.JdbcAccess.*;

/** Exact event-owned cleanup includes all booking history created by the test. */
final class P5Fixture implements AutoCloseable {
    final P3Fixture f = new P3Fixture();
    final long organizer = f.user("ORGANIZER"), customer = f.user("CUSTOMER");
    final long category = f.category(), venue = f.venue(), event;
    final BookingDao dao = new BookingDao();
    final List<Long> ids;

    P5Fixture(int seats) throws Exception {
        for (int i = 0; i < seats; i++) { f.seat(venue); }
        event = f.event(organizer, category, venue, f.prefix + "Booking event", "DRAFT", 30);
        InventorySetupIT.service(f).generate(organizer, event, "10");
        InventorySetupIT.service(f).publish(organizer, event);
        ids = f.access.read(c -> {
            var result = new java.util.ArrayList<Long>();
            try (var s = statement(c, "SELECT id FROM dbo.event_seats WHERE event_id=? ORDER BY id", event); var r = s.executeQuery()) {
                while (r.next()) { result.add(r.getLong(1)); }
            }
            return List.copyOf(result);
        });
    }

    String[] selection(int... indexes) {
        return java.util.Arrays.stream(indexes).mapToObj(index -> ids.get(index).toString()).toArray(String[]::new);
    }

    BookingService service() { return new BookingService(f.access, dao); }

    java.time.LocalDateTime deadline(long booking, int secondsFromNow) {
        return f.access.write(c -> {
            try (var s = statement(c, "SET NOCOUNT ON; DECLARE @deadline datetime2(3)=DATEADD(SECOND,?,SYSUTCDATETIME()); "
                    + "UPDATE dbo.bookings SET created_at=DATEADD(MINUTE,-5,@deadline),expires_at=@deadline WHERE id=? AND event_id=?; "
                    + "UPDATE dbo.event_seats SET hold_expires_at=@deadline WHERE current_booking_id=? AND event_id=? AND state='HELD'; "
                    + "SELECT @deadline;", secondsFromNow, booking, event, booking, event); var r = s.executeQuery()) {
                r.next(); return r.getObject(1, java.time.LocalDateTime.class);
            }
        });
    }

    long count(String table) {
        if (!List.of("bookings", "booking_items", "event_seats").contains(table)) { throw new IllegalArgumentException("Unsupported fixture table."); }
        return f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo." + table + " WHERE event_id=?", event));
    }

    @Override public void close() throws Exception {
        try {
            f.access.write(c -> {
                for (long owned : f.events) {
                    for (String sql : List.of(
                            "UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL WHERE event_id=?",
                            "DELETE p FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id WHERE b.event_id=?",
                            "DELETE dbo.booking_items WHERE event_id=?", "DELETE dbo.bookings WHERE event_id=?")) {
                        try (var s = statement(c, sql, owned)) { s.executeUpdate(); }
                    }
                }
                return null;
            });
            f.bookings.clear();
        } finally { f.close(); }
    }
}
