package com.eventbooking.integration;

import com.eventbooking.dao.BookingHistoryDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.service.BookingHistoryService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingHistoryIT {
    private BookingHistoryService service(P6Fixture x) {
        return new BookingHistoryService(x.f.access, x.bookings, x.payments, new BookingHistoryDao());
    }

    @Test void effectiveFiltersCountsAndStableTiePagingAreOwnerBoundAndReadOnly() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long other = x.f.user("CUSTOMER");
            List<Long> ids = x.f.access.write(c -> {
                LocalDateTime at = x.payments.millisNow(c);
                List<Long> rows = new ArrayList<>();
                for (int i = 0; i < 14; i++) {
                    String state = i == 0 ? "CANCELLED" : i == 1 ? "CONFIRMED" : "PENDING";
                    LocalDateTime created = i < 4 ? at.minusMinutes(10) : at;
                    rows.add(scalar(c, "INSERT dbo.bookings(booking_code,customer_id,event_id,status,total_amount,created_at,expires_at,confirmed_at,cancelled_at) "
                            + "OUTPUT INSERTED.id VALUES(?,?,?,?,0,?,?,?,?)", "BK-" + UUID.randomUUID(), i == 13 ? other : x.customer, x.event,
                            state, created, created.plusMinutes(5), state.equals("CONFIRMED") ? created : null, state.equals("CANCELLED") ? created : null));
                }
                return rows;
            });
            var before = x.snapshot();
            var history = service(x);
            var first = history.list(x.customer, "", 1, 10);
            var second = history.list(x.customer, "", 2, 10);
            assertEquals(13, first.total()); assertEquals(13, second.total());
            assertEquals(10, first.items().size()); assertEquals(3, second.items().size());
            List<Long> expected = new ArrayList<>();
            for (int i = 12; i >= 4; i--) { expected.add(ids.get(i)); }
            for (int i = 3; i >= 0; i--) { expected.add(ids.get(i)); }
            List<Long> actual = new ArrayList<>(first.items().stream().map(e -> e.booking().id()).toList());
            actual.addAll(second.items().stream().map(e -> e.booking().id()).toList());
            assertEquals(expected, actual);
            for (var pair : Map.of("PENDING", 9L, "EXPIRED", 2L, "CONFIRMED", 1L, "CANCELLED", 1L).entrySet()) {
                var filtered = history.list(x.customer, pair.getKey(), 1, 50);
                assertEquals(pair.getValue(), filtered.total());
                assertEquals(pair.getValue().intValue(), filtered.items().size());
                assertTrue(filtered.items().stream().allMatch(e -> e.getState().equals(pair.getKey())));
            }
            assertEquals(1, history.list(other, "", 1, 10).total());
            assertEquals(before, x.snapshot());
            assertEquals(11, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings WHERE customer_id=? AND event_id=? AND status='PENDING'", x.customer, x.event)).longValue());
        }
    }

    @Test void paymentHistoryIsBoundedAndStillAccessibleAfterStartAndReferenceDeactivation() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long booking = x.hold(0), other = x.f.user("CUSTOMER");
            for (int i = 0; i < 12; i++) { x.service().pay(x.customer, booking, P6Fixture.key(), "failure"); }
            x.service().pay(x.customer, booking, P6Fixture.key(), "success");
            var view = service(x).detail(x.customer, booking, 2, 10, null);
            assertEquals(13, view.attempts().total()); assertEquals(3, view.attempts().items().size());
            assertFalse(view.canPay());
            var first = service(x).detail(x.customer, booking, 1, 10, null);
            assertTrue(first.attempts().items().get(9).id() > view.attempts().items().get(0).id());
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE id=?", x.event);
                update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", x.base.category); return null;
            });
            var before = x.snapshot();
            assertEquals("CONFIRMED", service(x).detail(x.customer, booking, 1, 10, null).summary().getState());
            assertEquals(1, service(x).list(x.customer, "CONFIRMED", 1, 10).total());
            assertEquals(404, assertThrows(DomainException.class, () -> service(x).detail(other, booking, 1, 10, null)).getStatus());
            assertEquals(before, x.snapshot());
        }
    }

    @Test void offerUsesCompleteAllocationAndEffectiveExpiryWithoutChangingAnything() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long booking = x.hold(0);
            assertTrue(service(x).detail(x.customer, booking, 1, 10, null).canPay());
            var at = x.booking(booking).expiresAt();
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET state='HELD',current_booking_id=?,hold_expires_at=? WHERE id=?", booking, at, x.ids.get(1)); return null; });
            var before = x.snapshot();
            assertFalse(service(x).detail(x.customer, booking, 1, 10, null).canPay());
            assertEquals(before, x.snapshot());
            x.deadline(booking, -1);
            before = x.snapshot();
            var expired = service(x).detail(x.customer, booking, 1, 10, null);
            assertFalse(expired.canPay()); assertEquals("EXPIRED", expired.summary().getState());
            assertEquals("PENDING", expired.summary().booking().status());
            assertEquals(before, x.snapshot());
        }
    }
}
