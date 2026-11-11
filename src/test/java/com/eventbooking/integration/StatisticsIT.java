package com.eventbooking.integration;

import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.StatisticsDao;
import com.eventbooking.model.StatisticsSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsIT {
    private final StatisticsDao dao = new StatisticsDao();

    StatisticsSummary.Customer customer(P6Fixture x) {
        return x.f.access.read(c -> dao.customer(c, x.customer, new EventDao().now(c)));
    }

    StatisticsSummary.Organizer organizer(P6Fixture x) {
        return x.f.access.read(c -> dao.organizer(c, x.base.organizer, new EventDao().now(c)));
    }

    StatisticsSummary.Admin admin(P6Fixture x) { return x.f.access.read(dao::admin); }

    static void sales(StatisticsSummary.Sales actual, long tickets, String gross, String refunds) {
        assertEquals(tickets, actual.ticketsSold());
        assertEquals(new BigDecimal(gross), actual.grossPayments());
        assertEquals(new BigDecimal(refunds), actual.refundedPayments());
        assertEquals(new BigDecimal(gross).subtract(new BigDecimal(refunds)), actual.netRevenue());
    }

    private void counts(P6Fixture x, long total, long pending, long confirmed, long expired, long cancelled) {
        var actual = customer(x);
        assertEquals(new StatisticsSummary.Customer(total, pending, confirmed, expired, cancelled), actual);
        assertEquals(actual.totalBookings(), actual.pendingBookings() + actual.confirmedBookings()
                + actual.expiredBookings() + actual.cancelledBookings());
    }

    @Test void emptyOwnedScopesAreRealZerosAndAdminCountsIncludeInactiveAndCurrentAccounts() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long customer = f.user("CUSTOMER"), organizer = f.user("ORGANIZER");
            f.access.write(c -> { update(c, "UPDATE dbo.users SET is_active=0 WHERE id=?", customer); return null; });
            f.access.read(c -> {
                var now = new EventDao().now(c);
                assertEquals(new StatisticsSummary.Customer(0, 0, 0, 0, 0), dao.customer(c, customer, now));
                var owned = dao.organizer(c, organizer, now);
                assertEquals(0, owned.totalEvents()); assertEquals(0, owned.upcomingPublishedEvents());
                sales(owned.sales(), 0, "0.00", "0.00");
                var all = dao.admin(c);
                assertEquals(scalar(c, "SELECT COUNT_BIG(*) FROM dbo.users"), all.totalAccounts());
                assertEquals(1, scalar(c, "SELECT COUNT_BIG(*) FROM dbo.users WHERE id=?", f.admin));
                assertEquals(scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events"), all.totalEvents());
                assertEquals(scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings"), all.totalBookings());
                return null;
            });
        }
    }

    @Test void twoSeatReconciliationIncludesFailedAttemptsReplayRefundAndRepeatedCancellation() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            x.f.access.write(c -> { try (var s = statement(c, "UPDATE dbo.event_seats SET price=150000 WHERE event_id=?", x.event)) { assertEquals(2, s.executeUpdate()); } return null; });
            var initial = admin(x);
            counts(x, 0, 0, 0, 0, 0);
            long booking = x.hold(0, 1);
            counts(x, 1, 1, 0, 0, 0); sales(organizer(x).sales(), 0, "0.00", "0.00");
            x.service().pay(x.customer, booking, P6Fixture.key(), "failure");
            x.service().pay(x.customer, booking, P6Fixture.key(), "failure");
            counts(x, 1, 1, 0, 0, 0); sales(organizer(x).sales(), 0, "0.00", "0.00");
            String key = P6Fixture.key();
            x.service().pay(x.customer, booking, key, "success");
            counts(x, 1, 0, 1, 0, 0); sales(organizer(x).sales(), 2, "300000.00", "0.00");
            var paid = organizer(x);
            assertTrue(x.service().pay(x.customer, booking, key, "success").replay());
            assertEquals(paid, organizer(x));
            var all = admin(x);
            assertEquals(initial.totalBookings() + 1, all.totalBookings());
            assertEquals(initial.sales().ticketsSold() + 2, all.sales().ticketsSold());
            assertEquals(initial.sales().grossPayments().add(new BigDecimal("300000.00")), all.sales().grossPayments());
            x.service().cancel(x.customer, booking);
            counts(x, 1, 0, 0, 0, 1); sales(organizer(x).sales(), 0, "300000.00", "300000.00");
            var refunded = organizer(x);
            x.service().cancel(x.customer, booking);
            assertEquals(refunded, organizer(x));
            var finalAll = admin(x);
            assertEquals(initial.sales().ticketsSold(), finalAll.sales().ticketsSold());
            assertEquals(initial.sales().refundedPayments().add(new BigDecimal("300000.00")), finalAll.sales().refundedPayments());
            assertEquals(initial.sales().netRevenue(), finalAll.sales().netRevenue());
        }
    }

    @Test void equalValueMultiSeatPaymentsAreCountedIndividuallyAndFreeTicketsStillCount() throws Exception {
        try (P6Fixture x = new P6Fixture(5)) {
            long first = x.hold(0, 1), second = x.hold(2, 3);
            x.service().pay(x.customer, first, P6Fixture.key(), "failure");
            x.service().pay(x.customer, first, P6Fixture.key(), "success");
            x.service().pay(x.customer, second, P6Fixture.key(), "success");
            x.f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET price=0 WHERE id=?", x.ids.get(4)); return null; });
            long free = x.hold(4);
            x.service().pay(x.customer, free, P6Fixture.key(), "success");
            counts(x, 3, 0, 3, 0, 0);
            sales(organizer(x).sales(), 5, "40.00", "0.00");
        }
    }

    @Test void ownersAndHistoricalInactiveReferencesDoNotLeakOrHideReceipts() throws Exception {
        try (P6Fixture x = new P6Fixture(1); P6Fixture other = new P6Fixture(1)) {
            long ownBooking = x.hold(0), otherBooking = other.hold(0);
            x.service().pay(x.customer, ownBooking, P6Fixture.key(), "success");
            other.service().pay(other.customer, otherBooking, P6Fixture.key(), "success");
            x.f.event(x.base.organizer, x.base.category, x.base.venue, x.f.prefix + "Draft", "DRAFT", 40);
            x.f.event(x.base.organizer, x.base.category, x.base.venue, x.f.prefix + "Closed", "CLOSED", -2);
            x.f.event(x.base.organizer, x.base.category, x.base.venue, x.f.prefix + "Cancelled", "CANCELLED", 40);
            x.f.event(x.base.organizer, x.base.category, x.base.venue, x.f.prefix + "Upcoming", "PUBLISHED", 40);
            x.f.access.write(c -> {
                update(c, "UPDATE dbo.events SET starts_at=DATEADD(DAY,-1,SYSUTCDATETIME()) WHERE id=?", x.event);
                update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", x.base.category);
                update(c, "UPDATE dbo.venues SET is_active=0 WHERE id=?", x.base.venue);
                update(c, "UPDATE dbo.users SET is_active=0 WHERE id=?", x.customer);
                return null;
            });
            var owned = organizer(x);
            assertEquals(5, owned.totalEvents()); assertEquals(1, owned.upcomingPublishedEvents());
            sales(owned.sales(), 1, "10.00", "0.00");
            sales(organizer(other).sales(), 1, "10.00", "0.00");
            assertEquals(1, organizer(other).upcomingPublishedEvents());
            counts(x, 1, 0, 1, 0, 0); counts(other, 1, 0, 1, 0, 0);
        }
    }

    @Test void effectiveExpiryUsesOneExactSqlTimeAndDoesNotWriteBookingState() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            LocalDateTime now = x.f.access.read(c -> {
                try (var s = statement(c, "SELECT CAST(SYSUTCDATETIME() AS datetime2(3))"); var r = s.executeQuery()) { r.next(); return r.getObject(1, LocalDateTime.class); }
            });
            x.f.access.write(c -> {
                for (String kind : new String[]{"future", "equal", "past", "EXPIRED", "CONFIRMED", "CANCELLED"}) {
                    LocalDateTime expiry = kind.equals("future") ? now.plusMinutes(2) : kind.equals("equal") ? now : now.minusMinutes(1);
                    String status = java.util.Set.of("EXPIRED", "CONFIRMED", "CANCELLED").contains(kind) ? kind : "PENDING";
                    scalar(c, "INSERT dbo.bookings(booking_code,customer_id,event_id,total_amount,created_at,expires_at,status,confirmed_at,cancelled_at) "
                                    + "OUTPUT INSERTED.id VALUES (?,?,?,0,?,?,?,?,?)", "ST-" + UUID.randomUUID(), x.customer, x.event,
                            expiry.minusMinutes(5), expiry, status, status.equals("CONFIRMED") ? expiry.minusMinutes(4) : null,
                            status.equals("CANCELLED") ? expiry.minusMinutes(4) : null);
                }
                return null;
            });
            var before = x.snapshot();
            assertEquals(new StatisticsSummary.Customer(6, 1, 1, 3, 1), x.f.access.read(c -> dao.customer(c, x.customer, now)));
            assertEquals(before, x.snapshot(), "Statistics must not normalize expired rows or allocate seats.");
        }
    }

    @Test void decimalAggregatesMayExceedTheSingleBookingAmountLimit() throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            x.f.access.write(c -> { try (var s = statement(c, "UPDATE dbo.event_seats SET price=9999999999999999.99 WHERE event_id=?", x.event)) { assertEquals(2, s.executeUpdate()); } return null; });
            long first = x.hold(0), second = x.hold(1);
            x.service().pay(x.customer, first, P6Fixture.key(), "success");
            x.service().pay(x.customer, second, P6Fixture.key(), "success");
            sales(organizer(x).sales(), 2, "19999999999999999.98", "0.00");
        }
    }
}
