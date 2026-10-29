package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.BookingItem;
import com.eventbooking.model.Role;
import com.eventbooking.service.*;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingContentionIT {
    @Test void twoCustomersCompetingForOneSeatHaveExactlyOneCompleteWinner() throws Exception {
        race(new int[]{0}, new int[]{0}, 1, false);
    }

    @Test void overlappingSetsNeverLeaveTheLosersNonoverlappingSeatHeld() throws Exception {
        race(new int[]{0, 1}, new int[]{1, 2}, 1, false);
    }

    @Test void disjointSetsBothCommitWithSeparateCodesAndExactItems() throws Exception {
        race(new int[]{0, 1}, new int[]{2, 3}, 2, false);
    }

    @Test void simultaneousRepeatsByTheSameCustomerDoNotRenewOrDuplicate() throws Exception {
        race(new int[]{0}, new int[]{0}, 1, true);
    }

    private void race(int[] first, int[] second, int winners, boolean sameCustomer) throws Exception {
        try (P5Fixture x = new P5Fixture(4)) {
            long other = sameCustomer ? x.customer : x.f.user("CUSTOMER");
            var outcomes = InventoryRace.run(x.f,
                    access -> new BookingService(access, x.dao).hold(x.customer, x.event, x.selection(first)),
                    access -> new BookingService(access, x.dao).hold(other, x.event, x.selection(second)));
            assertEquals(winners, outcomes.stream().filter(result -> result.status() == 200).count());
            assertTrue(outcomes.stream().allMatch(result -> result.status() == 200 || result.status() == 409));
            int seats = winners == 2 ? first.length + second.length : first.length;
            assertFinal(x, winners, seats);
            for (int i = 0; i < 2; i++) {
                if (outcomes.get(i).status() == 200) {
                    long id = (Long) outcomes.get(i).value();
                    var items = x.f.access.read(c -> x.dao.items(c, id));
                    List<Long> expected = Arrays.stream(i == 0 ? first : second).mapToObj(index -> x.ids.get(index)).sorted().toList();
                    assertEquals(expected, items.stream().map(BookingItem::eventSeatId).toList());
                }
            }
        }
    }

    @Test void productionClaimSqlCompetesOnIndependentConnectionsWithoutTheManagementApplicationLock() throws Exception {
        for (boolean overlap : new boolean[]{false, true}) {
            try (P5Fixture x = new P5Fixture(3)) {
                long other = x.f.user("CUSTOMER");
                var executor = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
                Set<Long> sessions = ConcurrentHashMap.newKeySet();
                try {
                    var first = executor.submit(() -> rawHold(x, x.customer, new int[]{0, 1}, ready, start, sessions));
                    var second = executor.submit(() -> rawHold(x, other, overlap ? new int[]{1, 2} : new int[]{2}, ready, start, sessions));
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    assertEquals(2, sessions.size(), "Contention requires two real SQL sessions.");
                    start.countDown();
                    List<Integer> statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                    assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 409));
                    assertEquals(overlap ? 1 : 2, statuses.stream().filter(status -> status == 200).count());
                    assertFinal(x, overlap ? 1 : 2, overlap ? 2 : 3);
                } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
            }
        }
    }

    private int rawHold(P5Fixture x, long customer, int[] indexes, CountDownLatch ready, CountDownLatch start, Set<Long> sessions) throws Exception {
        try (Connection c = x.f.db.source.getConnection()) {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
            sessions.add(scalar(c, "SELECT @@SPID"));
            c.setAutoCommit(false);
            try {
                var draft = x.dao.create(c, customer, x.event, "BK-" + UUID.randomUUID());
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                List<BookingItem> items = new ArrayList<>();
                for (int index : indexes) {
                    var item = x.dao.claim(c, customer, x.event, draft, x.ids.get(index));
                    x.dao.addItem(c, draft.id(), x.event, item);
                    items.add(item);
                }
                x.dao.finish(c, customer, x.event, draft, BookingRules.total(items.stream().map(BookingItem::unitPrice).toList()));
                c.commit();
                return 200;
            } catch (DomainException rejected) {
                c.rollback(); return rejected.getStatus();
            } catch (SQLException failed) {
                c.rollback();
                if (failed.getErrorCode() == 1205 || failed.getErrorCode() == 1222) { return 409; }
                throw failed;
            } catch (Throwable failed) { c.rollback(); throw failed; }
        }
    }

    @Test void customerRoleChangeAndHoldRespectTheExistingHistoryGuard() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            var profile = new AdminUserService(x.f.access, new AdminUserDao(), new com.eventbooking.security.PasswordHasher()).find(x.f.admin, x.customer);
            var outcomes = InventoryRace.run(x.f,
                    access -> new BookingService(access, x.dao).hold(x.customer, x.event, x.selection(0)),
                    access -> {
                        new AdminUserService(access, new AdminUserDao(), new com.eventbooking.security.PasswordHasher())
                                .save(x.f.admin, x.customer, profile.fullName(), profile.email(), Role.ORGANIZER, true);
                        return "changed";
                    });
            assertEquals(1, outcomes.stream().filter(result -> result.status() == 200).count());
            boolean booked = outcomes.get(0).status() == 200;
            assertEquals(booked ? 409 : 403, outcomes.get(booked ? 1 : 0).status());
            assertFinal(x, booked ? 1 : 0, booked ? 1 : 0);
        }
    }

    @Test void categoryDisableCannotBypassPublishedEventGuardWhileHolding() throws Exception {
        try (P5Fixture x = new P5Fixture(1)) {
            var outcomes = InventoryRace.run(x.f,
                    access -> new BookingService(access, x.dao).hold(x.customer, x.event, x.selection(0)),
                    access -> { new ReferenceService(access, new ReferenceDao()).categoryStatus(x.f.admin, x.category, false); return "disabled"; });
            assertEquals(200, outcomes.get(0).status()); assertEquals(409, outcomes.get(1).status());
            assertTrue(x.f.references.category(x.f.admin, x.category).active());
            assertFinal(x, 1, 1);
        }
    }

    @Test void disablingACustomerOrClosingAnEventSerializesWithHoldCreation() throws Exception {
        for (boolean disableCustomer : new boolean[]{true, false}) {
            try (P5Fixture x = new P5Fixture(1)) {
                var outcomes = InventoryRace.run(x.f,
                        access -> new BookingService(access, x.dao).hold(x.customer, x.event, x.selection(0)),
                        access -> access.write(c -> {
                            // Test-owned state change uses the same coordination as management.
                            update(c, disableCustomer ? "UPDATE dbo.users SET is_active=0 WHERE id=?" : "UPDATE dbo.events SET status='CLOSED' WHERE id=?",
                                    disableCustomer ? x.customer : x.event);
                            return "changed";
                        }));
                assertEquals(200, outcomes.get(1).status());
                boolean booked = outcomes.get(0).status() == 200;
                if (!booked) { assertEquals(disableCustomer ? 403 : 409, outcomes.get(0).status()); }
                assertFinal(x, booked ? 1 : 0, booked ? 1 : 0);
                assertThrows(DomainException.class, () -> x.service().hold(x.customer, x.event, x.selection(0)));
            }
        }
    }

    static void assertFinal(P5Fixture x, long bookings, long held) {
        assertEquals(bookings, x.count("bookings")); assertEquals(held, x.count("booking_items"));
        assertEquals(held, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='HELD' AND current_booking_id IS NOT NULL", x.event)).longValue());
        assertEquals(0L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings b WHERE b.event_id=? AND (NOT EXISTS (SELECT 1 FROM dbo.booking_items bi WHERE bi.booking_id=b.id) OR b.total_amount<>(SELECT SUM(bi.unit_price) FROM dbo.booking_items bi WHERE bi.booking_id=b.id) OR EXISTS (SELECT 1 FROM dbo.event_seats es WHERE es.current_booking_id=b.id AND es.hold_expires_at<>b.expires_at))", x.event)).longValue());
        BookingHoldIT.available(x, x.ids.size() - held);
    }
}
