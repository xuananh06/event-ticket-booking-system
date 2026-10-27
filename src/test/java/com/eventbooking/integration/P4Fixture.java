package com.eventbooking.integration;

import java.time.LocalDateTime;
import java.util.UUID;
import static com.eventbooking.dao.JdbcAccess.*;

/** Test-only history/state fixtures; no application booking or hold endpoint exists. */
final class P4Fixture implements AutoCloseable {
    final P3Fixture f = new P3Fixture();

    P4Fixture() throws Exception { }

    long history(long customer, long event, long inventory, String state, LocalDateTime expiry) {
        long booking = f.access.write(c -> scalar(c,
                "INSERT dbo.bookings(booking_code,customer_id,event_id,total_amount,created_at,expires_at,status,confirmed_at) OUTPUT INSERTED.id VALUES (?,?,?,1,?,?,?,?)",
                "INV-" + UUID.randomUUID(), customer, event, expiry.minusMinutes(1), expiry,
                state.equals("SOLD") ? "CONFIRMED" : "PENDING", state.equals("SOLD") ? expiry.minusMinutes(1) : null));
        f.bookings.add(booking);
        f.access.write(c -> {
            update(c, "UPDATE dbo.event_seats SET state=?,current_booking_id=?,hold_expires_at=? WHERE event_id=? AND id=?",
                    state, booking, state.equals("HELD") ? expiry : null, event, inventory);
            scalar(c, "INSERT dbo.booking_items(booking_id,event_seat_id,event_id,unit_price,seat_label_snapshot) OUTPUT INSERTED.id VALUES (?,?,?,1,N'Fixture seat')", booking, inventory, event);
            return null;
        });
        return booking;
    }

    @Override public void close() throws Exception {
        try {
            f.access.write(c -> {
                for (long event : f.events) {
                    try (var s = statement(c, "UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL WHERE event_id=?", event)) { s.executeUpdate(); }
                }
                for (long booking : f.bookings) {
                    try (var s = statement(c, "DELETE dbo.booking_items WHERE booking_id=?", booking)) { s.executeUpdate(); }
                }
                return null;
            });
        } finally { f.close(); }
    }
}
