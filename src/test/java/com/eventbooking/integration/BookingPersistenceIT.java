package com.eventbooking.integration;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.BookingItem;
import com.eventbooking.service.BookingRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingPersistenceIT {
    @Test void databaseCreatesOneClockPairAndOwnerBoundImmutableItemSnapshots() throws Exception {
        try (P5Fixture fixture = new P5Fixture(1)) {
            var f = fixture.f;
            String code = "BK-" + UUID.randomUUID();
            long booking = f.access.write(c -> {
                var draft = fixture.dao.create(c, fixture.customer, fixture.event, code);
                assertEquals(Duration.ofMinutes(5), Duration.between(draft.createdAt(), draft.expiresAt()));
                assertEquals(0, draft.createdAt().getNano() % 1000000);
                var item = new BookingItem(fixture.ids.get(0), new BigDecimal("10.00"), BookingRules.seatLabel("A", 1));
                update(c, "UPDATE dbo.event_seats SET state='HELD',current_booking_id=?,hold_expires_at=? WHERE id=?",
                        draft.id(), draft.expiresAt(), item.eventSeatId());
                fixture.dao.addItem(c, draft.id(), fixture.event, item);
                update(c, "UPDATE dbo.bookings SET total_amount=10 WHERE id=?", draft.id());
                return draft.id();
            });
            var header = f.access.read(c -> fixture.dao.owned(c, fixture.customer, booking, false));
            assertEquals(code, header.code());
            assertEquals(new BigDecimal("10.00"), header.total());
            assertEquals("PENDING", header.status());
            assertEquals(1, f.access.read(c -> fixture.dao.items(c, booking)).size());
            long other = f.user("CUSTOMER");
            assertEquals(404, assertThrows(DomainException.class, () -> f.access.read(c -> fixture.dao.owned(c, other, booking, false))).getStatus());
            assertEquals(1, fixture.count("bookings"));
            assertEquals(1, fixture.count("booking_items"));
        }
    }
}
