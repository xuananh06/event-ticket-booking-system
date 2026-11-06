package com.eventbooking.integration;

import com.eventbooking.dao.PaymentDao;
import com.eventbooking.exception.*;
import com.eventbooking.model.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class CancellationIT {
    @Test void livePendingCancellationReleasesWholeHoldAndRetainsFailedAttemptsAndItems() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1);
            x.service().pay(x.customer, id, P6Fixture.key(), "failure");
            Booking original = x.booking(id); var items = x.items(id); var before = x.snapshot();
            var result = x.service().cancel(x.customer, id);
            assertFalse(result.repeated()); assertEquals("CANCELLED", result.booking().status());
            assertEquals(original.expiresAt(), result.booking().expiresAt()); assertEquals(original.createdAt(), result.booking().createdAt());
            assertEquals(original.total(), result.booking().total()); assertNull(result.booking().confirmedAt());
            assertNotNull(result.booking().cancelledAt()); assertEquals(items, x.items(id));
            assertEquals(before.get("items"), x.snapshot().get("items")); assertEquals(before.get("payments"), x.snapshot().get("payments"));
            available(x, 2); assertEquals(1, x.attempts(id)); assertEquals(0, x.successes(id));
            var after = x.snapshot(); assertTrue(x.service().cancel(x.customer, id).repeated()); assertEquals(after, x.snapshot());
        }
    }

    @Test void confirmedCancellationIgnoresOldHoldExpiryAndInactiveSaleReferencesAndRefundsExactlyOnce() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1); String key = P6Fixture.key();
            Payment paid = x.service().pay(x.customer, id, key, "success").attempt();
            x.deadline(id, -1);
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", x.base.category);
                update(c, "UPDATE dbo.venues SET is_active=0 WHERE id=?", x.base.venue);
                update(c, "UPDATE dbo.seats SET is_active=0 WHERE id=?", x.f.seats.get(0));
                update(c, "UPDATE dbo.events SET status='CLOSED' WHERE id=?", x.event);
                update(c, "UPDATE dbo.users SET is_active=0 WHERE id=?", x.base.organizer); return null;
            });
            Booking before = x.booking(id); var items = x.items(id);
            var cancelled = x.service().cancel(x.customer, id);
            assertEquals("CANCELLED", cancelled.booking().status()); assertEquals(before.confirmedAt(), cancelled.booking().confirmedAt());
            assertEquals(before.expiresAt(), cancelled.booking().expiresAt()); assertEquals(items, x.items(id));
            var replay = x.service().pay(x.customer, id, key, "success"); assertTrue(replay.replay());
            Payment refunded = replay.attempt();
            assertEquals("REFUNDED", refunded.status()); assertEquals("success", refunded.originalOutcome());
            assertEquals(paid.id(), refunded.id()); assertEquals(paid.key(), refunded.key()); assertEquals(paid.fingerprint(), refunded.fingerprint());
            assertEquals(paid.amount(), refunded.amount()); assertEquals(paid.createdAt(), refunded.createdAt()); assertEquals(paid.succeededAt(), refunded.succeededAt());
            assertEquals(cancelled.booking().cancelledAt(), refunded.refundedAt()); assertFalse(refunded.refundedAt().isBefore(refunded.succeededAt()));
            available(x, 2); assertEquals(1, x.attempts(id)); assertEquals(1, x.successes(id));
            var after = x.snapshot();
            assertTrue(x.service().cancel(x.customer, id).repeated());
            assertEquals(after, x.snapshot());
        }
    }

    @Test void freeConfirmedBookingUsesAZeroFullRefundWithoutDeletingTheSuccessfulAttempt() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=0 WHERE id=?", x.ids.get(0)); return null; });
            long id = x.hold(0); String key = P6Fixture.key();
            x.service().pay(x.customer, id, key, "success"); x.service().cancel(x.customer, id);
            var attempt = x.service().pay(x.customer, id, key, "success").attempt();
            assertEquals(new BigDecimal("0.00"), attempt.amount()); assertEquals("REFUNDED", attempt.status());
            assertEquals(1, x.successes(id)); available(x, 1);
        }
    }

    @Test void cancellationRequiresTheCurrentActiveCustomerOwnerEvenForRepeatedRequests() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0), other = x.f.user("CUSTOMER");
            for (boolean cancelled : new boolean[]{false, true}) {
                if (cancelled) { x.service().cancel(x.customer, id); }
                var before = x.snapshot();
                assertEquals(404, assertThrows(DomainException.class, () -> x.service().cancel(other, id)).getStatus());
                String email = x.f.email(x.customer);
                for (String role : List.of("ADMIN", "ORGANIZER")) {
                    x.f.db.changeAccount(email, role, true);
                    assertEquals(403, assertThrows(DomainException.class, () -> x.service().cancel(x.customer, id)).getStatus());
                }
                x.f.db.changeAccount(email, "CUSTOMER", false);
                assertEquals(403, assertThrows(DomainException.class, () -> x.service().cancel(x.customer, id)).getStatus());
                x.f.db.changeAccount(email, "CUSTOMER", true); assertEquals(before, x.snapshot());
            }
        }
    }

    @Test void expiredPendingAndStartedEventsRejectWithoutNormalizingOrRefunding() throws Exception {
        for (boolean confirmed : new boolean[]{false, true}) {
            try (P6Fixture x = new P6Fixture(1)) {
                long id = x.hold(0);
                if (confirmed) { x.service().pay(x.customer, id, P6Fixture.key(), "success"); }
                else { x.deadline(id, -1); reject(x, id); }
                for (int seconds : new int[]{0, -1}) {
                    x.f.access.write(c -> { update(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,?,SYSUTCDATETIME()) WHERE id=?", seconds, x.event); return null; });
                    reject(x, id);
                }
            }
        }
    }

    @Test void inconsistentTotalItemsAllocationAndPaymentNeverPartiallyRefundOrRepair() throws Exception {
        for (boolean confirmed : new boolean[]{false, true}) {
            for (String fault : List.of("total", "empty", "missing", "extra", "state", "payment")) {
                try (P6Fixture x = new P6Fixture(2)) {
                    long id = x.hold(0);
                    if (confirmed) { x.service().pay(x.customer, id, P6Fixture.key(), "success"); }
                    var expires = x.booking(id).expiresAt();
                    x.f.access.write(c -> {
                        switch (fault) {
                            case "total" -> update(c, "UPDATE dbo.bookings SET total_amount=total_amount+0.01 WHERE id=?", id);
                            case "empty" -> update(c, "DELETE dbo.booking_items WHERE booking_id=?", id);
                            case "missing" -> update(c, "UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL WHERE id=?", x.ids.get(0));
                            case "extra" -> update(c, "UPDATE dbo.event_seats SET state='HELD',current_booking_id=?,hold_expires_at=? WHERE id=?", id, expires, x.ids.get(1));
                            case "state" -> {
                                if (confirmed) { update(c, "UPDATE dbo.event_seats SET state='HELD',hold_expires_at=? WHERE id=?", expires, x.ids.get(0)); }
                                else { update(c, "UPDATE dbo.event_seats SET state='SOLD',hold_expires_at=NULL WHERE id=?", x.ids.get(0)); }
                            }
                            case "payment" -> {
                                if (confirmed) { update(c, "UPDATE dbo.payments SET amount=amount+0.01 WHERE booking_id=?", id); }
                                else { update(c, "UPDATE dbo.event_seats SET hold_expires_at=DATEADD(SECOND,-1,hold_expires_at) WHERE id=?", x.ids.get(0)); }
                            }
                            default -> throw new AssertionError(fault);
                        }
                        return null;
                    });
                    reject(x, id);
                }
            }
        }
    }

    @Test void injectedFailureAfterRefundRollsBackTheFinancialAndBookingState() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1); x.service().pay(x.customer, id, P6Fixture.key(), "success");
            PaymentDao broken = new PaymentDao() {
                @Override public void refund(Connection c, Booking b, Payment p, LocalDateTime at) throws SQLException {
                    super.refund(c, b, p, at); throw new SQLException("Injected post-refund failure.");
                }
            };
            var before = x.snapshot(); assertThrows(ServiceUnavailableException.class, () -> x.service(broken).cancel(x.customer, id));
            assertEquals(before, x.snapshot()); x.assertConfirmed(id, 2);
        }
    }

    @Test void injectedFailureAfterFirstReleaseRollsBackAllSeatsAndRefundForBothStates() throws Exception {
        for (boolean confirmed : new boolean[]{false, true}) {
            try (P6Fixture x = new P6Fixture(2)) {
                long id = x.hold(0, 1); if (confirmed) { x.service().pay(x.customer, id, P6Fixture.key(), "success"); }
                PaymentDao broken = new PaymentDao() {
                    @Override public void release(Connection c, Booking b, long inventory, String state) throws SQLException {
                        super.release(c, b, inventory, state); throw new SQLException("Injected partial-release failure.");
                    }
                };
                var before = x.snapshot(); assertThrows(ServiceUnavailableException.class, () -> x.service(broken).cancel(x.customer, id));
                assertEquals(before, x.snapshot());
            }
        }
    }

    @Test void rebookingCannotBeChangedByOldPaymentReplayRepeatedCancellationOrExpiration() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long id = x.hold(0); String failedKey = P6Fixture.key(), paidKey = P6Fixture.key();
            x.service().pay(x.customer, id, failedKey, "failure"); x.service().pay(x.customer, id, paidKey, "success");
            x.service().cancel(x.customer, id);
            long other = x.f.user("CUSTOMER"), current = x.base.service().hold(other, x.event, x.base.selection(0));
            x.f.access.write(c -> { update(c, "UPDATE dbo.events SET starts_at=SYSUTCDATETIME() WHERE id=?", x.event); return null; });
            var before = x.snapshot();
            assertTrue(x.service().cancel(x.customer, id).repeated());
            assertEquals("REFUNDED", x.service().pay(x.customer, id, paidKey, "success").attempt().status());
            assertEquals("FAILED", x.service().pay(x.customer, id, failedKey, "failure").attempt().status());
            assertEquals(409, assertThrows(DomainException.class, () -> x.service().pay(x.customer, id, paidKey, "failure")).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> x.service().pay(x.customer, id, P6Fixture.key(), "success")).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> x.base.service().expire(x.customer, id)).getStatus());
            assertEquals(before, x.snapshot());
            assertEquals(1, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats es JOIN dbo.bookings b ON b.id=es.current_booking_id WHERE es.id=? AND es.current_booking_id=? AND es.state='HELD' AND es.hold_expires_at=b.expires_at", x.ids.get(0), current)).longValue());
        }
    }

    private static void reject(P6Fixture x, long id) {
        var before = x.snapshot();
        assertEquals(409, assertThrows(DomainException.class, () -> x.service().cancel(x.customer, id)).getStatus());
        assertEquals(before, x.snapshot());
    }
    static void available(P6Fixture x, int count) {
        assertEquals(count, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='AVAILABLE' AND current_booking_id IS NULL AND hold_expires_at IS NULL", x.event)).longValue());
    }
}
