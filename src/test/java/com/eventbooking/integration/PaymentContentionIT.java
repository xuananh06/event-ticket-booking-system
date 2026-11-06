package com.eventbooking.integration;

import com.eventbooking.exception.DomainException;
import com.eventbooking.service.PaymentService;
import com.eventbooking.service.PaymentTestWork;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentContentionIT {
    private enum Requests { SAME_CONTENT, CHANGED_OUTCOME, DIFFERENT_KEYS }

    @Test void productionSameKeySameContentReturnsOneAttemptAndItsReplay() throws Exception {
        race(Requests.SAME_CONTENT, false);
    }

    @Test void productionSameKeyDifferentOutcomeReturnsOneRecordedAttemptAndOneConflict() throws Exception {
        race(Requests.CHANGED_OUTCOME, false);
    }

    @Test void productionDifferentKeysCannotCreateTwoSuccessfulPayments() throws Exception {
        race(Requests.DIFFERENT_KEYS, false);
    }

    @Test void rawSameKeySameContentUsesBookingLocksToReturnReplayWithoutApplicationLock() throws Exception {
        race(Requests.SAME_CONTENT, true);
    }

    @Test void rawSameKeyDifferentOutcomeConflictsWithoutApplicationLock() throws Exception {
        race(Requests.CHANGED_OUTCOME, true);
    }

    @Test void rawDifferentKeysCannotCreateTwoSuccessesWithoutApplicationLock() throws Exception {
        race(Requests.DIFFERENT_KEYS, true);
    }

    private void race(Requests requests, boolean raw) throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long booking = x.hold(0, 1);
            var original = x.booking(booking);
            var items = x.items(booking);
            String firstKey = P6Fixture.key();
            String secondKey = requests == Requests.DIFFERENT_KEYS ? P6Fixture.key() : firstKey;
            String secondOutcome = requests == Requests.CHANGED_OUTCOME ? "failure" : "success";
            List<InventoryRace.Outcome> outcomes = raw
                    ? independent(x, booking, firstKey, secondKey, secondOutcome)
                    : InventoryRace.run(x.f,
                            access -> new PaymentService(access, x.bookings, x.payments).pay(x.customer, booking, firstKey, "success"),
                            access -> new PaymentService(access, x.bookings, x.payments).pay(x.customer, booking, secondKey, secondOutcome));
            assertTrue(outcomes.stream().allMatch(o -> o.status() == 200 || o.status() == 409));
            assertEquals(1, x.attempts(booking));
            assertEquals(original.createdAt(), x.booking(booking).createdAt());
            assertEquals(original.expiresAt(), x.booking(booking).expiresAt());
            assertEquals(original.total(), x.booking(booking).total());
            assertEquals(items, x.items(booking));
            if (requests == Requests.SAME_CONTENT) {
                assertEquals(2, outcomes.stream().filter(o -> o.status() == 200).count(), "A duplicate must resolve to recorded replay, not a generic conflict.");
                var first = (PaymentService.Result) outcomes.get(0).value();
                var second = (PaymentService.Result) outcomes.get(1).value();
                assertEquals(first.attempt(), second.attempt());
                assertNotEquals(first.replay(), second.replay());
                x.assertConfirmed(booking, 2);
            } else {
                assertEquals(1, outcomes.stream().filter(o -> o.status() == 200).count());
                assertEquals(1, outcomes.stream().filter(o -> o.status() == 409).count());
                var committed = (PaymentService.Result) outcomes.stream().filter(o -> o.status() == 200).findFirst().orElseThrow().value();
                assertFalse(committed.replay());
                if (committed.attempt().originalOutcome().equals("success")) { x.assertConfirmed(booking, 2); }
                else {
                    assertEquals(Requests.CHANGED_OUTCOME, requests);
                    assertEquals("FAILED", committed.attempt().status());
                    assertEquals(0, x.successes(booking));
                    x.assertHeld(booking, 2);
                }
            }
            assertTrue(x.successes(booking) <= 1);
            var beforeReplay = x.snapshot();
            var committed = (PaymentService.Result) outcomes.stream().filter(o -> o.status() == 200).findFirst().orElseThrow().value();
            var replay = x.service().pay(x.customer, booking, committed.attempt().key(), committed.attempt().originalOutcome());
            assertTrue(replay.replay());
            assertEquals(committed.attempt(), replay.attempt());
            assertEquals(beforeReplay, x.snapshot());
        }
    }

    private List<InventoryRace.Outcome> independent(P6Fixture x, long booking, String firstKey,
                                                   String secondKey, String secondOutcome) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Set<Long> sessions = ConcurrentHashMap.newKeySet();
        try {
            var first = executor.submit(() -> transaction(x, booking, firstKey, "success", ready, start, sessions));
            var second = executor.submit(() -> transaction(x, booking, secondKey, secondOutcome, ready, start, sessions));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            assertEquals(2, sessions.size(), "Core locking evidence requires independent real SQL sessions.");
            start.countDown();
            return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private InventoryRace.Outcome transaction(P6Fixture x, long booking, String key, String outcome,
                                               CountDownLatch ready, CountDownLatch start, Set<Long> sessions) throws Exception {
        try (Connection c = x.f.db.source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
                sessions.add(scalar(c, "SELECT @@SPID"));
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                var result = PaymentTestWork.pay(x.service(), c, x.customer, booking, key, outcome);
                c.commit();
                return new InventoryRace.Outcome(result, 200);
            } catch (DomainException rejected) {
                c.rollback();
                return new InventoryRace.Outcome(null, rejected.getStatus());
            } catch (SQLException failed) {
                c.rollback();
                if (failed.getErrorCode() == 1205 || failed.getErrorCode() == 1222) {
                    return new InventoryRace.Outcome(null, 409);
                }
                throw failed;
            } catch (Throwable failed) {
                c.rollback();
                throw failed;
            }
        }
    }
}
