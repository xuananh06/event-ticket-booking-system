package com.eventbooking.integration;

import com.eventbooking.dao.PaymentDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.Booking;
import com.eventbooking.model.Payment;
import com.eventbooking.service.PaymentRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentIT {
    @Test void successConfirmsCompleteAllocationUsingImmutableSnapshotAmountAndOneSqlTime() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=12.34 WHERE id=?", x.ids.get(1)); return null; });
            long id = x.hold(0, 1);
            Booking before = x.booking(id);
            var items = x.items(id);
            // Current inventory pricing is not authority to rewrite the accepted charge.
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=999 WHERE id=?", x.ids.get(0)); return null; });
            String key = P6Fixture.key();
            var result = x.service().pay(x.customer, id, key, "success");
            assertFalse(result.replay());
            Payment attempt = result.attempt();
            assertEquals(id, attempt.bookingId());
            assertEquals(key, attempt.key());
            assertEquals(new BigDecimal("22.34"), attempt.amount());
            assertEquals("SUCCEEDED", attempt.status());
            assertEquals("success", attempt.originalOutcome());
            assertEquals(PaymentRules.fingerprint(id, "success", before.total()), attempt.fingerprint());
            assertEquals(attempt.createdAt(), attempt.succeededAt());
            assertNull(attempt.refundedAt());
            assertEquals(0, attempt.createdAt().getNano() % 1_000_000);
            assertEquals(before.expiresAt(), x.booking(id).expiresAt());
            assertEquals(before.createdAt(), x.booking(id).createdAt());
            assertEquals(before.total(), x.booking(id).total());
            assertEquals(items, x.items(id));
            assertEquals(1, x.attempts(id));
            x.assertConfirmed(id, 2);
        }
    }

    @Test void failedAttemptPreservesOriginalHoldAndReplayWritesNothing() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1);
            Map<String, String> before = x.snapshot();
            String key = P6Fixture.key();
            var failed = x.service().pay(x.customer, id, key, "failure");
            assertFalse(failed.replay());
            assertEquals("FAILED", failed.attempt().status());
            assertEquals("failure", failed.attempt().originalOutcome());
            assertEquals(new BigDecimal("20.00"), failed.attempt().amount());
            assertNull(failed.attempt().succeededAt());
            assertNull(failed.attempt().refundedAt());
            Map<String, String> after = x.snapshot();
            for (String table : List.of("bookings", "items", "inventory")) { assertEquals(before.get(table), after.get(table)); }
            x.assertHeld(id, 2);
            assertEquals(1, x.attempts(id));
            assertEquals(0, x.successes(id));
            var replay = x.service().pay(x.customer, id, key, "failure");
            assertTrue(replay.replay());
            assertEquals(failed.attempt(), replay.attempt());
            assertEquals(after, x.snapshot());
        }
    }

    @Test void deliberateRetryUsesNewKeyAndOldFailureReplayShowsItsOriginalResult() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0);
            String failedKey = P6Fixture.key();
            var failed = x.service().pay(x.customer, id, failedKey, "failure");
            var succeeded = x.service().pay(x.customer, id, P6Fixture.key(), "success");
            assertFalse(succeeded.replay());
            x.assertConfirmed(id, 1);
            assertEquals(2, x.attempts(id));
            var before = x.snapshot();
            var replay = x.service().pay(x.customer, id, failedKey, "failure");
            assertTrue(replay.replay());
            assertEquals(failed.attempt(), replay.attempt());
            assertEquals("CONFIRMED", x.booking(id).status());
            assertEquals(before, x.snapshot());
            reject(x, id, P6Fixture.key(), "success");
        }
    }

    @Test void freeBookingStillRecordsExplicitFailureThenOneSuccessfulAttempt() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=0 WHERE id=?", x.ids.get(0)); return null; });
            long id = x.hold(0);
            assertEquals(new BigDecimal("0.00"), x.service().pay(x.customer, id, P6Fixture.key(), "failure").attempt().amount());
            x.assertHeld(id, 1);
            assertEquals(new BigDecimal("0.00"), x.service().pay(x.customer, id, P6Fixture.key(), "success").attempt().amount());
            x.assertConfirmed(id, 1);
            assertEquals(2, x.attempts(id));
        }
    }

    @Test void recordedSuccessAndFailureReplayAfterExpiryStartAndReferenceDeactivation() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long failedBooking = x.hold(0), paidBooking = x.hold(1);
            String failedKey = P6Fixture.key(), paidKey = P6Fixture.key();
            var failure = x.service().pay(x.customer, failedBooking, failedKey, "failure");
            var success = x.service().pay(x.customer, paidBooking, paidKey, "success");
            x.deadline(failedBooking, -1);
            x.deadline(paidBooking, -1);
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE id=?", x.event);
                update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", x.base.category);
                return null;
            });
            var before = x.snapshot();
            var failedReplay = x.service().pay(x.customer, failedBooking, failedKey, "failure");
            var paidReplay = x.service().pay(x.customer, paidBooking, paidKey, "success");
            assertTrue(failedReplay.replay()); assertTrue(paidReplay.replay());
            assertEquals(failure.attempt(), failedReplay.attempt());
            assertEquals(success.attempt(), paidReplay.attempt());
            assertEquals("CONFIRMED", x.booking(paidBooking).status());
            assertEquals(before, x.snapshot());
            reject(x, failedBooking, P6Fixture.key(), "failure");
            reject(x, paidBooking, paidKey, "failure");
        }
    }

    @Test void identicalKeyIsScopedToEachOwnedBooking() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long first = x.hold(0), second = x.hold(1);
            String key = P6Fixture.key();
            var failed = x.service().pay(x.customer, first, key, "failure");
            var succeeded = x.service().pay(x.customer, second, key, "success");
            assertNotEquals(failed.attempt().id(), succeeded.attempt().id());
            assertEquals(first, failed.attempt().bookingId());
            assertEquals(second, succeeded.attempt().bookingId());
            var before = x.snapshot();
            assertEquals(failed.attempt(), x.service().pay(x.customer, first, key, "failure").attempt());
            assertEquals(succeeded.attempt(), x.service().pay(x.customer, second, key, "success").attempt());
            assertEquals(before, x.snapshot());
        }
    }

    @Test void changingRecordedSemanticOutcomeConflictsWithoutAnyWrite() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            for (int i = 0; i < 2; i++) {
                long id = x.hold(i);
                String key = P6Fixture.key(), outcome = i == 0 ? "failure" : "success";
                x.service().pay(x.customer, id, key, outcome);
                reject(x, id, key, i == 0 ? "success" : "failure");
            }
        }
    }

    @Test void ownershipRoleAndLiveAccountAreRequiredBeforeReplayDisclosure() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0), other = x.f.user("CUSTOMER");
            String key = P6Fixture.key();
            x.service().pay(x.customer, id, key, "failure");
            var before = x.snapshot();
            assertEquals(404, assertThrows(DomainException.class, () -> x.service().pay(other, id, key, "failure")).getStatus());
            assertEquals(404, assertThrows(DomainException.class, () -> x.service().pay(x.customer, Long.MAX_VALUE, key, "failure")).getStatus());
            String email = x.f.email(x.customer);
            for (String role : List.of("ADMIN", "ORGANIZER")) {
                x.f.db.changeAccount(email, role, true);
                assertEquals(403, assertThrows(DomainException.class, () -> x.service().pay(x.customer, id, key, "failure")).getStatus());
            }
            x.f.db.changeAccount(email, "CUSTOMER", false);
            assertEquals(403, assertThrows(DomainException.class, () -> x.service().pay(x.customer, id, key, "failure")).getStatus());
            x.f.db.changeAccount(email, "CUSTOMER", true);
            assertEquals(before, x.snapshot());
            assertTrue(x.service().pay(x.customer, id, key, "failure").replay());
        }
    }

    @Test void malformedKeyAndUnsupportedOutcomeNeverPersistAnAttempt() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0);
            var before = x.snapshot();
            for (String key : new String[]{null, "", " ", P6Fixture.key() + " ", "x".repeat(101), "bad\nkey"}) {
                assertThrows(ValidationException.class, () -> x.service().pay(x.customer, id, key, "success"));
            }
            for (String outcome : new String[]{null, "", "SUCCEEDED", "refunded", "success "}) {
                assertThrows(ValidationException.class, () -> x.service().pay(x.customer, id, P6Fixture.key(), outcome));
            }
            assertEquals(before, x.snapshot());
        }
    }

    @Test void inconsistentStoredTotalAndEmptyItemsRejectWithoutRepairingHistory() throws Exception {
        for (boolean empty : new boolean[]{false, true}) {
            try (P6Fixture x = new P6Fixture(1)) {
                long id = x.hold(0);
                x.f.access.write(c -> {
                    if (empty) { update(c, "DELETE dbo.booking_items WHERE booking_id=?", id); }
                    else { update(c, "UPDATE dbo.bookings SET total_amount=total_amount+0.01 WHERE id=?", id); }
                    return null;
                });
                reject(x, id, P6Fixture.key(), "success");
                reject(x, id, P6Fixture.key(), "failure");
            }
        }
    }

    @Test void missingExtraSoldAndMismatchedDeadlineAllocationsRejectBothOutcomes() throws Exception {
        for (String fault : List.of("missing", "extra", "sold", "deadline")) {
            try (P6Fixture x = new P6Fixture(2)) {
                long id = x.hold(0);
                var expiresAt = x.booking(id).expiresAt();
                x.f.access.write(c -> {
                    switch (fault) {
                        case "missing" -> update(c, "UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL WHERE id=?", x.ids.get(0));
                        case "extra" -> update(c, "UPDATE dbo.event_seats SET state='HELD',current_booking_id=?,hold_expires_at=? WHERE id=?", id, expiresAt, x.ids.get(1));
                        case "sold" -> update(c, "UPDATE dbo.event_seats SET state='SOLD',hold_expires_at=NULL WHERE id=?", x.ids.get(0));
                        case "deadline" -> update(c, "UPDATE dbo.event_seats SET hold_expires_at=DATEADD(SECOND,-1,hold_expires_at) WHERE id=?", x.ids.get(0));
                        default -> throw new AssertionError(fault);
                    }
                    return null;
                });
                reject(x, id, P6Fixture.key(), "success");
                reject(x, id, P6Fixture.key(), "failure");
            }
        }
    }

    @Test void reassignedInventoryNeverAuthorizesPaymentForTheHistoricalBooking() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long old = x.hold(0);
            x.deadline(old, -1);
            long other = x.f.user("CUSTOMER");
            long current = x.base.service().hold(other, x.event, x.base.selection(0));
            x.deadline(old, 60);
            reject(x, old, P6Fixture.key(), "success");
            assertEquals(1, x.f.access.read(c -> scalar(c,
                    "SELECT COUNT_BIG(*) FROM dbo.event_seats es JOIN dbo.bookings b ON b.id=es.current_booking_id "
                            + "WHERE es.id=? AND es.current_booking_id=? AND es.state='HELD' AND es.hold_expires_at=b.expires_at", x.ids.get(0), current)).longValue());
        }
    }

    @Test void inactivePhysicalSeatRejectsPaymentWithoutChangingSnapshots() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0);
            x.f.access.write(c -> { update(c, "UPDATE dbo.seats SET is_active=0 WHERE id=?", x.f.seats.get(0)); return null; });
            reject(x, id, P6Fixture.key(), "success");
            reject(x, id, P6Fixture.key(), "failure");
        }
    }

    @Test void disabledSaleReferencesAndUnpublishedOrStartedEventsRejectNewAttempts() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0);
            for (String table : List.of("categories", "venues", "users")) {
                long target = table.equals("categories") ? x.base.category : table.equals("venues") ? x.base.venue : x.base.organizer;
                x.f.access.write(c -> { update(c, "UPDATE dbo." + table + " SET is_active=0 WHERE id=?", target); return null; });
                reject(x, id, P6Fixture.key(), "success");
                reject(x, id, P6Fixture.key(), "failure");
                x.f.access.write(c -> { update(c, "UPDATE dbo." + table + " SET is_active=1 WHERE id=?", target); return null; });
            }
            for (String state : List.of("DRAFT", "CLOSED", "CANCELLED")) {
                x.f.access.write(c -> { update(c, "UPDATE dbo.events SET status=? WHERE id=?", state, x.event); return null; });
                reject(x, id, P6Fixture.key(), "success");
                reject(x, id, P6Fixture.key(), "failure");
            }
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.events SET status='PUBLISHED',starts_at=SYSUTCDATETIME() WHERE id=?", x.event);
                return null;
            });
            reject(x, id, P6Fixture.key(), "success");
            reject(x, id, P6Fixture.key(), "failure");
        }
    }

    @Test void expiredOriginalHoldRejectsSuccessAndFailureWithoutRecordingOrRenewing() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0);
            x.deadline(id, -1);
            reject(x, id, P6Fixture.key(), "success");
            reject(x, id, P6Fixture.key(), "failure");
            assertEquals(0, x.attempts(id));
            assertEquals("PENDING", x.booking(id).status());
        }
    }

    @Test void failureAfterFinancialInsertRollsBackAndSameKeyCanThenCommitNormally() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0);
            String key = P6Fixture.key();
            PaymentDao injected = new PaymentDao() {
                @Override public Payment record(Connection c, Booking booking, String key, String fingerprint, String outcome, LocalDateTime at) throws SQLException {
                    super.record(c, booking, key, fingerprint, outcome, at);
                    throw new SQLException("Injected post-payment failure.");
                }
            };
            var before = x.snapshot();
            assertThrows(ServiceUnavailableException.class, () -> x.service(injected).pay(x.customer, id, key, "success"));
            assertEquals(before, x.snapshot());
            assertFalse(x.service().pay(x.customer, id, key, "success").replay());
            x.assertConfirmed(id, 1);
        }
    }

    @Test void failureAfterFirstInventorySaleRollsBackPaymentAndEverySeat() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1);
            AtomicInteger sold = new AtomicInteger();
            PaymentDao injected = new PaymentDao() {
                @Override public void sell(Connection c, Booking booking, long inventory) throws SQLException {
                    super.sell(c, booking, inventory);
                    if (sold.incrementAndGet() == 1) { throw new SQLException("Injected partial-sale failure."); }
                }
            };
            var before = x.snapshot();
            assertThrows(ServiceUnavailableException.class, () -> x.service(injected).pay(x.customer, id, P6Fixture.key(), "success"));
            assertEquals(1, sold.get());
            assertEquals(before, x.snapshot());
            x.assertHeld(id, 2);
            assertEquals(0, x.attempts(id));
        }
    }

    private static void reject(P6Fixture x, long booking, String key, String outcome) {
        var before = x.snapshot();
        assertEquals(409, assertThrows(DomainException.class, () -> x.service().pay(x.customer, booking, key, outcome)).getStatus());
        assertEquals(before, x.snapshot());
    }
}
