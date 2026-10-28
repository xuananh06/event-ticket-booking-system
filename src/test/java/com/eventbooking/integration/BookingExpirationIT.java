package com.eventbooking.integration;

import com.eventbooking.exception.DomainException;
import com.eventbooking.service.BookingRules;
import com.eventbooking.service.BookingService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.*;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingExpirationIT {
    @Test void checkoutIsOwnerBoundReadOnlyAndNeverRenewsBeforeOrAfterExpiry() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            long id = x.service().hold(x.customer, x.event, x.selection(0));
            var original = x.service().checkout(x.customer, id);
            assertEquals("PENDING", original.getState());
            assertTrue(original.getRemainingSeconds() > 0 && original.getRemainingSeconds() <= 300);
            assertEquals(original.booking(), x.service().checkout(x.customer, id).booking());
            long other = x.f.user("CUSTOMER");
            assertEquals(404, assertThrows(DomainException.class, () -> x.service().checkout(other, id)).getStatus());
            assertEquals(403, assertThrows(DomainException.class, () -> x.service().checkout(x.organizer, id)).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> x.service().expire(x.customer, id)).getStatus());
            var deadline = x.deadline(id, -1);
            // Boundary inputs derive from the persisted SQL clock, not a browser/JVM clock.
            assertEquals("PENDING", BookingRules.effectiveStatus("PENDING", deadline, deadline.minusNanos(1)));
            assertEquals("EXPIRED", BookingRules.effectiveStatus("PENDING", deadline, deadline));
            assertEquals("EXPIRED", BookingRules.effectiveStatus("PENDING", deadline, deadline.plusNanos(1)));
            var expired = x.service().checkout(x.customer, id);
            assertEquals("EXPIRED", expired.getState()); assertEquals(0, expired.getRemainingSeconds());
            assertTrue(expired.isCanNormalize());
            assertEquals("PENDING", expired.booking().status());
            assertEquals(deadline, expired.booking().expiresAt());
            assertEquals(expired.booking(), x.service().checkout(x.customer, id).booking());
            assertEquals(1L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE current_booking_id=? AND state='HELD'", id)).longValue());
        }
    }

    @Test void oldExpirationCannotReleaseReassignedSeatsAndPreservesBothItemHistories() throws Exception {
        try (P5Fixture x = new P5Fixture(3)) {
            long old = x.service().hold(x.customer, x.event, x.selection(0, 1));
            x.deadline(old, -1);
            var oldItems = x.f.access.read(c -> x.dao.items(c, old));
            long other = x.f.user("CUSTOMER");
            long current = x.service().hold(other, x.event, x.selection(1, 2));
            var newHeader = x.service().checkout(other, current).booking();
            assertEquals("PENDING", x.service().checkout(x.customer, old).booking().status());
            assertEquals(404, assertThrows(DomainException.class, () -> x.service().expire(other, old)).getStatus());
            x.service().expire(x.customer, old);
            x.service().expire(x.customer, old);
            assertEquals("EXPIRED", x.service().checkout(x.customer, old).booking().status());
            assertEquals(oldItems, x.f.access.read(c -> x.dao.items(c, old)));
            assertEquals(newHeader, x.service().checkout(other, current).booking());
            assertEquals(2L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE current_booking_id=? AND state='HELD' AND hold_expires_at=?", current, newHeader.expiresAt())).longValue());
            BookingHoldIT.available(x, 1);
            assertEquals(4, x.count("booking_items"));
        }
    }

    @Test void normalizationNeverReleasesSoldInventoryOrNonpendingStates() throws Exception {
        for (String state : new String[]{"CONFIRMED", "CANCELLED", "PENDING"}) {
            try (P5Fixture x = new P5Fixture(1)) {
                long id = x.service().hold(x.customer, x.event, x.selection(0));
                x.deadline(id, -1);
                x.f.access.write(c -> {
                    update(c, "UPDATE dbo.event_seats SET state='SOLD',hold_expires_at=NULL WHERE id=?", x.ids.get(0));
                    if (!state.equals("PENDING")) {
                        update(c, state.equals("CONFIRMED")
                                ? "UPDATE dbo.bookings SET status='CONFIRMED',confirmed_at=SYSUTCDATETIME() WHERE id=?"
                                : "UPDATE dbo.bookings SET status='CANCELLED',cancelled_at=SYSUTCDATETIME() WHERE id=?", id);
                    }
                    return null;
                });
                if (state.equals("PENDING")) { x.service().expire(x.customer, id); }
                else { assertEquals(409, assertThrows(DomainException.class, () -> x.service().expire(x.customer, id)).getStatus()); }
                assertEquals(1L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE id=? AND current_booking_id=? AND state='SOLD' AND hold_expires_at IS NULL", x.ids.get(0), id)).longValue());
                assertEquals(1, x.count("booking_items"));
            }
        }
    }

    @Test void expirationAndTakeoverRaceKeepsTheNewOwnerAndDeadline() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            long old = x.service().hold(x.customer, x.event, x.selection(0));
            x.deadline(old, -1);
            long other = x.f.user("CUSTOMER");
            var results = InventoryRace.run(x.f,
                    access -> { new BookingService(access, x.dao).expire(x.customer, old); return "expired"; },
                    access -> new BookingService(access, x.dao).hold(other, x.event, x.selection(0)));
            assertEquals(200, results.get(0).status()); assertEquals(200, results.get(1).status());
            long current = (Long) results.get(1).value();
            var deadline = x.service().checkout(other, current).booking().expiresAt();
            x.service().expire(x.customer, old);
            assertEquals(1L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE id=? AND current_booking_id=? AND hold_expires_at=? AND state='HELD'", x.ids.get(0), current, deadline)).longValue());
            assertEquals(2, x.count("bookings")); assertEquals(2, x.count("booking_items"));
        }
    }

    @Test void expiredTakeoverDoesNotWaitForOrModifyTheOldBookingLock() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            long old = x.service().hold(x.customer, x.event, x.selection(0));
            x.deadline(old, -1);
            long other = x.f.user("CUSTOMER");
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                blocker.setAutoCommit(false);
                try (var s = statement(blocker, "SELECT id FROM dbo.bookings WITH (UPDLOCK,HOLDLOCK) WHERE id=?", old); var r = s.executeQuery()) { assertTrue(r.next()); }
                var takeover = executor.submit(() -> x.service().hold(other, x.event, x.selection(0)));
                try { assertTrue(takeover.get(4, TimeUnit.SECONDS) > 0, "Takeover must not depend on the old booking lock."); }
                finally { blocker.rollback(); }
                assertEquals("PENDING", x.service().checkout(x.customer, old).booking().status());
                assertEquals(2, x.count("booking_items"));
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
}
