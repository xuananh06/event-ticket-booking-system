package com.eventbooking.integration;

import com.eventbooking.dao.BookingDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.model.BookingItem;
import com.eventbooking.service.BookingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingHoldIT {
    @Test void committedHoldUsesStoredPricesOneDeadlineAndNonemptySnapshotsAcrossIndependentEvents() throws Exception {
        try (P5Fixture x = new P5Fixture(3)) {
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.event_seats SET price=0 WHERE id=?", x.ids.get(0));
                update(c, "UPDATE dbo.event_seats SET price=12.34 WHERE id=?", x.ids.get(1));
                return null;
            });
            long id = x.service().hold(x.customer, x.event, x.selection(2, 0, 1));
            var booking = x.f.access.read(c -> x.dao.owned(c, x.customer, id, false));
            assertEquals("PENDING", booking.status());
            assertEquals(new BigDecimal("22.34"), booking.total());
            assertTrue(booking.code().matches("BK-[0-9a-f-]{36}"));
            assertEquals(Duration.ofMinutes(5), Duration.between(booking.createdAt(), booking.expiresAt()));
            assertEquals(3, x.f.access.read(c -> x.dao.items(c, id)).size());
            assertEquals(3L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='HELD' AND current_booking_id=? AND hold_expires_at=?", x.event, id, booking.expiresAt())).longValue());
            assertEquals(x.customer, x.f.access.read(c -> scalar(c, "SELECT customer_id FROM dbo.bookings WHERE id=?", id)).longValue());
            var snapshots = x.f.access.read(c -> x.dao.items(c, id));
            long other = x.f.event(x.organizer, x.category, x.venue, x.f.prefix + "Second event", "PUBLISHED", 40);
            for (long seat : x.f.seats) { x.f.inventory(other, seat, x.venue); }
            long inventory = x.f.access.read(c -> scalar(c, "SELECT MIN(id) FROM dbo.event_seats WHERE event_id=?", other));
            long second = x.service().hold(x.customer, other, new String[]{String.valueOf(inventory)});
            assertNotEquals(id, second);
            assertNotEquals(booking.code(), x.f.access.read(c -> x.dao.owned(c, x.customer, second, false)).code());
            assertEquals(snapshots, x.f.access.read(c -> x.dao.items(c, id)));
        }
    }

    @Test void foreignMissingInactiveSoldAndLiveHeldMembersRollbackTheWholeSelection() throws Exception {
        try (P5Fixture x = new P5Fixture(3)) {
            long otherEvent = x.f.event(x.organizer, x.category, x.venue, x.f.prefix + "Other", "PUBLISHED", 40);
            x.f.inventory(otherEvent, x.f.seats.get(0), x.venue);
            long foreign = x.f.access.read(c -> scalar(c, "SELECT id FROM dbo.event_seats WHERE event_id=?", otherEvent));
            for (long invalid : new long[]{foreign, Long.MAX_VALUE}) {
                reject(x, new String[]{x.ids.get(0).toString(), String.valueOf(invalid)});
                assertEquals(0, x.count("bookings"));
                assertEquals(0, x.count("booking_items"));
                available(x, 3);
            }
            x.f.access.write(c -> { update(c, "UPDATE dbo.seats SET is_active=0 WHERE id=?", x.f.seats.get(2)); return null; });
            reject(x, x.selection(0, 2));
            available(x, 3);
            x.f.access.write(c -> { update(c, "UPDATE dbo.seats SET is_active=1 WHERE id=?", x.f.seats.get(2)); return null; });
            long owner = x.service().hold(x.customer, x.event, x.selection(2));
            var deadline = x.f.access.read(c -> x.dao.owned(c, x.customer, owner, false)).expiresAt();
            long otherCustomer = x.f.user("CUSTOMER");
            assertEquals(409, assertThrows(DomainException.class, () -> x.service().hold(otherCustomer, x.event, x.selection(0, 2))).getStatus());
            assertEquals(1, x.count("bookings")); assertEquals(1, x.count("booking_items")); available(x, 2);
            assertEquals(deadline, x.f.access.read(c -> x.dao.owned(c, x.customer, owner, false)).expiresAt());
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.event_seats SET state='SOLD',hold_expires_at=NULL WHERE id=?", x.ids.get(2));
                update(c, "UPDATE dbo.bookings SET status='CONFIRMED',confirmed_at=SYSUTCDATETIME() WHERE id=?", owner);
                return null;
            });
            reject(x, x.selection(0, 2));
            assertEquals(1, x.count("bookings")); assertEquals(1, x.count("booking_items")); available(x, 2);
        }
    }

    @Test void actorEventAndActiveParentEligibilityAreRevalidatedInsideTheTransaction() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            for (long role : new long[]{x.organizer, x.f.admin}) {
                assertEquals(403, assertThrows(DomainException.class, () -> x.service().hold(role, x.event, x.selection(0))).getStatus());
            }
            x.f.db.changeAccount(x.f.email(x.customer), "CUSTOMER", false);
            assertEquals(403, assertThrows(DomainException.class, () -> x.service().hold(x.customer, x.event, x.selection(0))).getStatus());
            x.f.db.changeAccount(x.f.email(x.customer), "CUSTOMER", true);
            for (String state : List.of("DRAFT", "CLOSED", "CANCELLED")) {
                x.f.access.write(c -> { update(c, "UPDATE dbo.events SET status=? WHERE id=?", state, x.event); return null; });
                assertEquals(409, assertThrows(DomainException.class, () -> x.service().hold(x.customer, x.event, x.selection(0))).getStatus());
            }
            x.f.access.write(c -> { update(c, "UPDATE dbo.events SET status='PUBLISHED' WHERE id=?", x.event); return null; });
            for (String table : List.of("categories", "venues", "users")) {
                long target = table.equals("categories") ? x.category : table.equals("venues") ? x.venue : x.organizer;
                x.f.access.write(c -> { update(c, "UPDATE dbo." + table + " SET is_active=0 WHERE id=?", target); return null; });
                assertEquals(409, assertThrows(DomainException.class, () -> x.service().hold(x.customer, x.event, x.selection(0))).getStatus());
                x.f.access.write(c -> { update(c, "UPDATE dbo." + table + " SET is_active=1 WHERE id=?", target); return null; });
            }
            x.f.access.write(c -> { update(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE id=?", x.event); return null; });
            assertEquals(409, assertThrows(DomainException.class, () -> x.service().hold(x.customer, x.event, x.selection(0))).getStatus());
            assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items")); available(x, 1);
        }
    }

    @Test void aggregateOverflowAndInjectedFailureLeaveNoBookingItemsOrPartialHolds() throws Exception {
        try (P5Fixture x = new P5Fixture(2)) {
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=9999999999999999.99 WHERE id=?", x.ids.get(0)); return null; });
            reject(x, x.selection(0, 1));
            assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items")); available(x, 2);
            BookingDao failing = new BookingDao() {
                @Override public void addItem(Connection c, long booking, long event, BookingItem item) throws SQLException {
                    super.addItem(c, booking, event, item);
                    throw new SQLException("Injected item failure.");
                }
            };
            assertThrows(ServiceUnavailableException.class, () -> new BookingService(x.f.access, failing).hold(x.customer, x.event, x.selection(0, 1)));
            assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items")); available(x, 2);
        }
    }

    @Test void repeatedRequestsDoNotCreateOrRenewAnActiveHold() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            long id = x.service().hold(x.customer, x.event, x.selection(0));
            var before = x.f.access.read(c -> x.dao.owned(c, x.customer, id, false));
            reject(x, x.selection(0));
            assertEquals(before, x.f.access.read(c -> x.dao.owned(c, x.customer, id, false)));
            assertEquals(1, x.count("bookings")); assertEquals(1, x.count("booking_items"));
        }
    }

    @Test void fiftySeatsCommitButAnOversizedSelectionDoesNotWriteAndAFreeBookingIsValid() throws Exception {
        try (P5Fixture x = new P5Fixture(51)) {
            String[] all = x.ids.stream().map(String::valueOf).toArray(String[]::new);
            assertThrows(com.eventbooking.exception.ValidationException.class, () -> x.service().hold(x.customer, x.event, all));
            assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items")); available(x, 51);
            long id = x.service().hold(x.customer, x.event, java.util.Arrays.copyOf(all, 50));
            assertEquals(new BigDecimal("500.00"), x.service().checkout(x.customer, id).booking().total());
            assertEquals(50, x.service().checkout(x.customer, id).items().size());
            available(x, 1);
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=0 WHERE id=?", x.ids.get(50)); return null; });
            long free = x.service().hold(x.customer, x.event, new String[]{all[50]});
            assertEquals(new BigDecimal("0.00"), x.service().checkout(x.customer, free).booking().total());
            assertEquals(2, x.count("bookings")); assertEquals(51, x.count("booking_items")); available(x, 0);
        }
    }

    private static void reject(P5Fixture x, String[] selection) {
        assertEquals(409, assertThrows(DomainException.class, () -> x.service().hold(x.customer, x.event, selection)).getStatus());
    }

    static void available(P5Fixture x, long expected) {
        assertEquals(expected, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='AVAILABLE' AND current_booking_id IS NULL AND hold_expires_at IS NULL", x.event)).longValue());
    }
}
