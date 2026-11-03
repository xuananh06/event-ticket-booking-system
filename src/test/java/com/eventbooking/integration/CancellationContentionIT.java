package com.eventbooking.integration;

import com.eventbooking.exception.DomainException;
import com.eventbooking.service.PaymentService;
import com.eventbooking.service.PaymentTestWork;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class CancellationContentionIT {
    @Test void productionPaymentAndCancellationSerializeToOneConsistentCancelledResult() throws Exception { paymentCancel(false); }
    @Test void rawPaymentAndCancellationSerializeWithoutTheApplicationLock() throws Exception { paymentCancel(true); }
    @Test void productionRepeatedCancellationRefundsOnlyOnce() throws Exception { repeated(false); }
    @Test void rawRepeatedCancellationRefundsOnlyOnceWithoutTheApplicationLock() throws Exception { repeated(true); }

    private void paymentCancel(boolean raw) throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1); String key = P6Fixture.key();
            List<InventoryRace.Outcome> results = raw ? independent(x,
                    c -> PaymentTestWork.pay(x.service(), c, x.customer, id, key, "success"),
                    c -> PaymentTestWork.cancel(x.service(), c, x.customer, id))
                    : InventoryRace.run(x.f,
                    a -> new PaymentService(a, x.bookings, x.payments).pay(x.customer, id, key, "success"),
                    a -> new PaymentService(a, x.bookings, x.payments).cancel(x.customer, id));
            assertEquals(200, results.get(1).status());
            assertTrue(results.get(0).status() == 200 || results.get(0).status() == 409);
            assertEquals("CANCELLED", x.booking(id).status()); CancellationIT.available(x, 2);
            long attempts = results.get(0).status() == 200 ? 1 : 0;
            assertEquals(attempts, x.attempts(id)); assertEquals(attempts, x.successes(id));
            if (attempts == 1) {
                assertEquals("REFUNDED", x.service().pay(x.customer, id, key, "success").attempt().status());
                assertNotNull(x.booking(id).confirmedAt());
            } else { assertNull(x.booking(id).confirmedAt()); }
            var before = x.snapshot(); assertTrue(x.service().cancel(x.customer, id).repeated()); assertEquals(before, x.snapshot());
        }
    }

    private void repeated(boolean raw) throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1); String key = P6Fixture.key(); x.service().pay(x.customer, id, key, "success");
            List<InventoryRace.Outcome> results = raw ? independent(x,
                    c -> PaymentTestWork.cancel(x.service(), c, x.customer, id), c -> PaymentTestWork.cancel(x.service(), c, x.customer, id))
                    : InventoryRace.run(x.f,
                    a -> new PaymentService(a, x.bookings, x.payments).cancel(x.customer, id),
                    a -> new PaymentService(a, x.bookings, x.payments).cancel(x.customer, id));
            assertTrue(results.stream().allMatch(r -> r.status() == 200));
            var first = (PaymentService.Cancellation) results.get(0).value(); var second = (PaymentService.Cancellation) results.get(1).value();
            assertNotEquals(first.repeated(), second.repeated()); assertEquals(first.booking(), second.booking());
            assertEquals(1, x.successes(id)); assertEquals(1, x.attempts(id)); CancellationIT.available(x, 2);
            assertEquals(x.booking(id).cancelledAt(), x.service().pay(x.customer, id, key, "success").attempt().refundedAt());
        }
    }

    @Test void expiredPaymentCannotPreventTakeoverOrAlterTheNewOwnersDeadline() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0), other = x.f.user("CUSTOMER"); x.deadline(id, -1);
            var results = InventoryRace.run(x.f,
                    a -> new PaymentService(a, x.bookings, x.payments).pay(x.customer, id, P6Fixture.key(), "success"),
                    a -> new com.eventbooking.service.BookingService(a, x.bookings).hold(other, x.event, x.base.selection(0)));
            assertEquals(409, results.get(0).status()); assertEquals(200, results.get(1).status());
            long current = (Long) results.get(1).value(); var before = x.snapshot();
            assertEquals(409, assertThrows(DomainException.class, () -> x.service().cancel(x.customer, id)).getStatus());
            assertEquals(before, x.snapshot());
            x.base.service().expire(x.customer, id);
            assertEquals(before.get("inventory"), x.snapshot().get("inventory"), "Old expiration must preserve the newer owner's full allocation and exact deadline.");
            assertEquals(current, x.f.access.read(c -> scalar(c, "SELECT current_booking_id FROM dbo.event_seats WHERE id=?", x.ids.get(0))).longValue());
            assertEquals(0, x.attempts(id));
        }
    }

    @Test void expiredPaymentAndExplicitExpirationCannotCreateFinancialState() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0); x.deadline(id, -1);
            var results = InventoryRace.run(x.f,
                    a -> new PaymentService(a, x.bookings, x.payments).pay(x.customer, id, P6Fixture.key(), "failure"),
                    a -> { new com.eventbooking.service.BookingService(a, x.bookings).expire(x.customer, id); return true; });
            assertEquals(409, results.get(0).status()); assertEquals(200, results.get(1).status());
            assertEquals("EXPIRED", x.booking(id).status()); assertEquals(0, x.attempts(id)); CancellationIT.available(x, 1);
        }
    }

    @Test void cancellationAndNewReservationPreserveAnyNewOwnerAcrossAllOldReplays() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long old = x.hold(0), other = x.f.user("CUSTOMER"); String key = P6Fixture.key();
            x.service().pay(x.customer, old, key, "success");
            var results = InventoryRace.run(x.f,
                    a -> new PaymentService(a, x.bookings, x.payments).cancel(x.customer, old),
                    a -> new com.eventbooking.service.BookingService(a, x.bookings).hold(other, x.event, x.base.selection(0)));
            assertEquals(200, results.get(0).status()); assertTrue(results.get(1).status() == 200 || results.get(1).status() == 409);
            long current = results.get(1).status() == 200 ? (Long) results.get(1).value() : x.base.service().hold(other, x.event, x.base.selection(0));
            var before = x.snapshot();
            assertTrue(x.service().cancel(x.customer, old).repeated()); assertTrue(x.service().pay(x.customer, old, key, "success").replay());
            assertEquals(before, x.snapshot());
            assertEquals(current, x.f.access.read(c -> scalar(c, "SELECT current_booking_id FROM dbo.event_seats WHERE id=?", x.ids.get(0))).longValue());
        }
    }

    private List<InventoryRace.Outcome> independent(P6Fixture x, Work<Object> first, Work<Object> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Set<Long> sessions = ConcurrentHashMap.newKeySet();
        try {
            var a = executor.submit(() -> transaction(x, first, ready, start, sessions));
            var b = executor.submit(() -> transaction(x, second, ready, start, sessions));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); assertEquals(2, sessions.size()); start.countDown();
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private InventoryRace.Outcome transaction(P6Fixture x, Work<Object> work, CountDownLatch ready, CountDownLatch start, Set<Long> sessions) throws Exception {
        try (Connection c = x.f.db.source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); c.setAutoCommit(false);
            try {
                sessions.add(scalar(c, "SELECT @@SPID")); ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                Object value = work.run(c); c.commit(); return new InventoryRace.Outcome(value, 200);
            } catch (DomainException rejected) { c.rollback(); return new InventoryRace.Outcome(null, rejected.getStatus()); }
            catch (SQLException failed) {
                c.rollback(); if (failed.getErrorCode() == 1205 || failed.getErrorCode() == 1222) { return new InventoryRace.Outcome(null, 409); } throw failed;
            } catch (Throwable failed) { c.rollback(); throw failed; }
        }
    }
}
