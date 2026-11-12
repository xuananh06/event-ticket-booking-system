package com.eventbooking.integration;

import com.eventbooking.dao.StatisticsDao;
import com.eventbooking.model.StatisticsSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

import static com.eventbooking.dao.JdbcAccess.*;
import static com.eventbooking.integration.HttpBrowser.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsHttpCheck {
    @Test void deployedRoleSummariesReconcileAndRetainSecurityAndReadOnlyBehavior() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        for (String route : List.of("customer/home", "organizer/home", "admin/home")) {
            guest.redirect(guest.get(route + "?userId=1&role=ADMIN"), "login");
            guest.redirect(guest.head(route), "login");
        }
        status(404, guest.get("WEB-INF/views/account/statistics.jspf"));
        status(404, guest.get("WEB-INF/views/account/sales-statistics.jspf"));
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            var login = guest.get("login"); status(200, login);
            var failed = guest.post("login", fields("_csrf", token(login.body()), "identifier", "customer@example.test", "password", "irrelevant"));
            status(503, failed); assertFalse(failed.body().contains("id=\"metric-"));
            System.out.println("HTTP statistics guest and unavailable boundaries: PASS");
            return;
        }
        try (P6Fixture x = new P6Fixture(2); P6Fixture other = new P6Fixture(1)) {
            long emptyCustomer = x.f.user("CUSTOMER"), emptyOrganizer = x.f.user("ORGANIZER");
            x.f.access.write(c -> {
                for (long actor : new long[]{x.customer, x.base.organizer, x.f.admin}) {
                    update(c, "UPDATE dbo.users SET full_name=? WHERE id=?", "Statistics <member> & account", actor);
                }
                try (var s = statement(c, "UPDATE dbo.event_seats SET price=150000 WHERE event_id=?", x.event)) { assertEquals(2, s.executeUpdate()); }
                return null;
            });
            long unrelated = other.hold(0);
            other.service().pay(other.customer, unrelated, P6Fixture.key(), "success");
            customer(page(x, emptyCustomer, "customer/home"), 0, 0, 0, 0, 0);
            var empty = page(x, emptyOrganizer, "organizer/home");
            count(empty, "total-events", 0); count(empty, "upcoming-published-events", 0);
            sales(empty, 0, "0.00", "0.00");
            customer(page(x, x.customer, "customer/home"), 0, 0, 0, 0, 0);
            var own = page(x, x.base.organizer, "organizer/home");
            count(own, "total-events", 1); sales(own, 0, "0.00", "0.00");
            customer(page(other, other.customer, "customer/home"), 1, 0, 1, 0, 0);
            sales(page(other, other.base.organizer, "organizer/home"), 1, "10.00", "0.00");
            var baseline = x.f.access.read(c -> new StatisticsDao().admin(c));
            identityLinksAndScope(x, other);

            HttpBrowser c = login(x, x.customer, "customer/home");
            var selection = c.get("customer/events/seats?eventId=" + x.event); status(200, selection);
            var hold = FinancialForms.form(selection, "hold");
            var held = c.post(hold.action(), hold.fields(), "eventSeatId", x.ids.stream().map(Object::toString).toList());
            status(303, held);
            var id = Pattern.compile("bookingId=([0-9]+)$").matcher(held.headers().firstValue("Location").orElseThrow());
            assertTrue(id.find()); long booking = Long.parseLong(id.group(1));
            checkpoint(x, baseline, 1, 0, 0, 0, "0.00", "0.00");

            c = login(x, x.customer, "customer/home");
            var failed = FinancialForms.form(c.get("customer/bookings/checkout?bookingId=" + booking), "pay");
            status(303, c.post(failed.action(), failed.submit("failure")));
            checkpoint(x, baseline, 1, 0, 0, 0, "0.00", "0.00");

            c = login(x, x.customer, "customer/home");
            var successful = FinancialForms.form(c.get("customer/bookings/checkout?bookingId=" + booking), "pay");
            status(303, c.post(successful.action(), successful.submit("success")));
            var paid = x.snapshot();
            status(303, c.post(successful.action(), successful.submit("success")));
            assertEquals(paid, x.snapshot()); assertEquals(2, x.attempts(booking));
            checkpoint(x, baseline, 0, 1, 0, 2, "300000.00", "0.00");

            c = login(x, x.customer, "customer/home");
            var cancellation = FinancialForms.form(c.get("customer/bookings/cancel?bookingId=" + booking), "cancel");
            var cancelledFields = new LinkedHashMap<>(cancellation.fields()); cancelledFields.put("confirm", "yes");
            status(303, c.post(cancellation.action(), cancelledFields));
            var refunded = x.snapshot();
            status(303, c.post(cancellation.action(), cancelledFields));
            assertEquals(refunded, x.snapshot());
            checkpoint(x, baseline, 0, 0, 1, 0, "300000.00", "300000.00");

            long expiring = x.hold(0); x.deadline(expiring, -1);
            var expired = x.snapshot();
            customer(page(x, x.customer, "customer/home"), 2, 0, 0, 1, 1);
            HttpBrowser head = login(x, x.customer, "customer/home");
            status(200, head.head("customer/home"));
            assertEquals(expired, x.snapshot());
            unavailableSummaryIsAnError(x, booking);
            authorizationAndLogout(x, emptyCustomer, emptyOrganizer);
        }
        System.out.println("HTTP role statistics, scoped zeros, financial reconciliation, escaping, links, read-only requests and security: PASS");
    }

    private void checkpoint(P6Fixture x, StatisticsSummary.Admin baseline, long pending, long confirmed, long cancelled,
                            long tickets, String gross, String refunds) throws Exception {
        customer(page(x, x.customer, "customer/home"), 1, pending, confirmed, 0, cancelled);
        var own = page(x, x.base.organizer, "organizer/home");
        count(own, "total-events", 1); count(own, "upcoming-published-events", 1);
        sales(own, tickets, gross, refunds);
        var admin = page(x, x.f.admin, "admin/home");
        count(admin, "total-accounts", baseline.totalAccounts());
        count(admin, "total-events", baseline.totalEvents());
        count(admin, "total-bookings", baseline.totalBookings() + 1);
        sales(admin, baseline.sales().ticketsSold() + tickets,
                baseline.sales().grossPayments().add(new BigDecimal(gross)).toPlainString(),
                baseline.sales().refundedPayments().add(new BigDecimal(refunds)).toPlainString());
    }

    private void identityLinksAndScope(P6Fixture x, P6Fixture other) throws Exception {
        for (long actor : new long[]{x.customer, x.base.organizer, x.f.admin}) {
            String route = actor == x.customer ? "customer/home" : actor == x.f.admin ? "admin/home" : "organizer/home";
            HttpBrowser browser = login(x, actor, route);
            var home = browser.get(route); status(200, home);
            assertTrue(home.body().contains("Statistics &lt;member&gt; &amp; account"));
            assertFalse(home.body().contains("Statistics <member>"));
            assertTrue(home.body().contains("aria-labelledby=\"statistics-heading\""));
            var beforeHead = x.snapshot();
            status(200, browser.head(route));
            assertEquals(beforeHead, x.snapshot(), "Role-home HEAD must not change booking or payment history.");
            for (String wrong : List.of("customer/home", "organizer/home", "admin/home")) {
                if (!route.equals(wrong)) { var denied = browser.get(wrong); status(403, denied); assertFalse(denied.body().contains("id=\"metric-")); }
            }
            String query = "?customerId=" + other.customer + "&organizerId=" + other.base.organizer + "&userId=" + other.f.admin + "&role=ADMIN";
            var spoof = browser.get(route + query); status(200, spoof);
            if (actor == x.customer) {
                customer(spoof, 0, 0, 0, 0, 0);
                for (String label : List.of("Total bookings", "Pending bookings", "Confirmed bookings", "Expired bookings", "Cancelled bookings")) {
                    String link = PaymentHttpCheck.link(home, label);
                    assertTrue(link.startsWith(browser.path("customer/bookings")));
                    status(200, browser.get(link));
                }
                assertFalse(home.body().contains("metric-gross-payments"));
            } else if (actor == x.base.organizer) {
                count(spoof, "total-events", 1); sales(spoof, 0, "0.00", "0.00");
                status(200, browser.get(PaymentHttpCheck.link(home, "Manage your events")));
                assertTrue(home.body().contains("does not guarantee purchase eligibility"));
            } else {
                status(200, browser.get(PaymentHttpCheck.link(home, "Manage accounts")));
            }
        }
    }

    private void unavailableSummaryIsAnError(P6Fixture x, long booking) throws Exception {
        HttpBrowser browser = login(x, x.customer, "customer/home");
        try (Connection blocker = x.f.db.source.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                update(blocker, "UPDATE dbo.bookings SET total_amount=total_amount WHERE id=? AND event_id=?", booking, x.event);
                var unavailable = browser.get("customer/home"); status(503, unavailable);
                assertFalse(unavailable.body().contains("id=\"metric-"), "An unavailable summary must not render zero cards.");
            } finally { blocker.rollback(); }
        }
        customer(page(x, x.customer, "customer/home"), 2, 0, 0, 1, 1);
    }

    private void authorizationAndLogout(P6Fixture x, long emptyCustomer, long emptyOrganizer) throws Exception {
        HttpBrowser inactive = login(x, emptyCustomer, "customer/home");
        x.f.db.changeAccount(x.f.email(emptyCustomer), "CUSTOMER", false);
        inactive.redirect(inactive.get("customer/home"), "login");
        HttpBrowser changed = login(x, emptyOrganizer, "organizer/home");
        x.f.db.changeAccount(x.f.email(emptyOrganizer), "CUSTOMER", true);
        var denied = changed.get("organizer/home"); status(403, denied);
        changed.redirect(changed.post("logout", fields("_csrf", token(denied.body()))), "");
        changed.redirect(changed.get("customer/home"), "login");
        for (long actor : new long[]{x.customer, x.base.organizer, x.f.admin}) {
            String route = actor == x.customer ? "customer/home" : actor == x.f.admin ? "admin/home" : "organizer/home";
            HttpBrowser browser = login(x, actor, route);
            var home = browser.get(route); status(200, home);
            browser.redirect(browser.post("logout", fields("_csrf", token(home.body()))), "");
            browser.redirect(browser.get(route), "login");
        }
    }

    private static HttpBrowser login(P6Fixture x, long actor, String route) throws Exception {
        HttpBrowser browser = new HttpBrowser(); browser.login(x.f.email(actor), P3Fixture.PASSWORD, route); return browser;
    }

    private static HttpResponse<String> page(P6Fixture x, long actor, String route) throws Exception {
        var page = login(x, actor, route).get(route); status(200, page);
        assertTrue(page.body().contains("All-time statistics"));
        assertTrue(page.body().contains("data-role=\"" + route.substring(0, route.indexOf('/')).toUpperCase(java.util.Locale.ROOT) + "\""));
        return page;
    }

    private static String metric(HttpResponse<String> page, String key) {
        var matcher = Pattern.compile("<p\\b[^>]*id=\"metric-" + Pattern.quote(key) + "\"[^>]*>\\s*([0-9]+(?:\\.[0-9]{2})?)\\s*</p>").matcher(page.body());
        assertTrue(matcher.find(), "Missing numeric summary metric: " + key);
        String value = matcher.group(1); assertFalse(matcher.find(), "Duplicate summary metric: " + key); return value;
    }

    private static void count(HttpResponse<String> page, String key, long expected) { assertEquals(expected, Long.parseLong(metric(page, key)), key); }

    private static void customer(HttpResponse<String> page, long total, long pending, long confirmed, long expired, long cancelled) {
        count(page, "total-bookings", total); count(page, "pending-bookings", pending); count(page, "confirmed-bookings", confirmed);
        count(page, "expired-bookings", expired); count(page, "cancelled-bookings", cancelled);
        assertEquals(total, pending + confirmed + expired + cancelled);
    }

    private static void sales(HttpResponse<String> page, long tickets, String gross, String refunds) {
        count(page, "tickets-sold", tickets);
        assertEquals(gross, metric(page, "gross-payments")); assertEquals(refunds, metric(page, "refunded-payments"));
        assertEquals(new BigDecimal(gross).subtract(new BigDecimal(refunds)).toPlainString(), metric(page, "net-revenue"));
        assertTrue(page.body().contains("Simulated payments in VND"));
    }
}
