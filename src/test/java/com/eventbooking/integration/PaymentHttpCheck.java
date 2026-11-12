package com.eventbooking.integration;

import org.junit.jupiter.api.Test;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.*;
import static com.eventbooking.dao.JdbcAccess.*;
import static com.eventbooking.integration.HttpBrowser.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentHttpCheck {
    @Test void deployedSimulationReplayOwnedHistoryAndSecurity() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        for (String route : List.of("customer/bookings", "customer/bookings/detail?bookingId=1", "customer/bookings/pay")) {
            guest.redirect(guest.get(route), "login");
        }
        guest.redirect(guest.post("customer/bookings/pay", fields("_csrf", token(guest.get("login").body()), "bookingId", "1", "paymentKey", P6Fixture.key(), "outcome", "success")), "login");
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            status(200, guest.get("health")); status(200, guest.get("login"));
            status(503, guest.post("login", fields("_csrf", token(guest.get("login").body()), "identifier", "customer@campus.test", "password", "irrelevant")));
            System.out.println("HTTP payment guest boundaries and unavailable foundation: PASS"); return;
        }
        try (P6Fixture x = new P6Fixture(12)) {
            long other = x.f.user("CUSTOMER");
            long paid = flow(x);
            long pending = x.hold(0);
            rolesAndOwnership(x, other, pending);
            negativeForms(x, pending);
            sessionReplay(x, paid);
            history(x, other, paid);
        }
    }

    private long flow(P6Fixture x) throws Exception {
        HttpBrowser c = login(x, x.customer, "customer/home");
        var select = c.get("customer/events/seats?eventId=" + x.event + "&page=2&size=10"); status(200, select);
        var hold = FinancialForms.form(select, "hold");
        assertTrue(select.body().contains("id=\"select-" + x.ids.get(11) + "\""));
        Map<String, String> selection = new LinkedHashMap<>(hold.fields()); selection.put("eventSeatId", "" + x.ids.get(11));
        var held = c.post(hold.action(), selection); status(303, held);
        String checkout = held.headers().firstValue("Location").orElseThrow();
        Matcher identity = Pattern.compile("bookingId=([0-9]+)$").matcher(checkout); assertTrue(identity.find());
        long id = Long.parseLong(identity.group(1));
        var original = x.booking(id); var items = x.items(id);
        var first = c.get(checkout); status(200, first);
        var form = FinancialForms.form(first, "pay");
        assertEquals(c.path("customer/bookings/pay"), form.action()); assertFalse(form.action().contains("?"));
        Map<String, String> failedFields = form.submit("failure");
        failedFields.putAll(fields("amount", "0", "total_amount", "0", "customer_id", "1", "booking_status", "REFUNDED", "succeeded_at", "9999-12-31", "refund_amount", "999"));
        var before = x.snapshot();
        var failed = follow(c, c.post(form.action(), failedFields), id);
        assertTrue(failed.body().contains("Original outcome: <strong>failure</strong>"));
        assertTrue(failed.body().contains("Current booking state: <strong>PENDING</strong>"));
        assertEquals(original, x.booking(id)); assertEquals(items, x.items(id)); x.assertHeld(id, 1);
        assertEquals(1, x.attempts(id)); assertEquals(0, x.successes(id));
        assertEquals(before.get("inventory"), x.snapshot().get("inventory"));
        var afterFailure = x.snapshot();
        var replay = follow(c, c.post(form.action(), failedFields), id);
        assertTrue(replay.body().contains("Recorded attempt replayed")); assertEquals(afterFailure, x.snapshot());
        var changed = new LinkedHashMap<>(form.submit("success"));
        var conflict = c.post(form.action(), changed); status(409, conflict);
        assertEquals(form.fields().get("paymentKey"), FinancialForms.form(conflict, "pay").fields().get("paymentKey"));
        assertEquals(afterFailure, x.snapshot());
        String retryUrl = link(replay, "Open a new simulation attempt");
        var retry = FinancialForms.form(c.get(retryUrl), "pay");
        assertNotEquals(form.fields().get("paymentKey"), retry.fields().get("paymentKey"));
        var success = follow(c, c.post(retry.action(), retry.submit("success")), id);
        assertTrue(success.body().contains("Current booking state: <strong>CONFIRMED</strong>"));
        assertTrue(success.body().contains("This booking is confirmed. Its payment history remains available."));
        assertFalse(success.body().contains("This reservation is read-only."));
        x.assertConfirmed(id, 1); assertEquals(2, x.attempts(id));
        var confirmed = x.snapshot();
        var oldFailure = follow(c, c.post(form.action(), failedFields), id);
        assertTrue(oldFailure.body().contains("Original outcome: <strong>failure</strong>"));
        assertTrue(oldFailure.body().contains("Current booking state: <strong>CONFIRMED</strong>"));
        follow(c, c.post(retry.action(), retry.submit("success")), id);
        assertEquals(confirmed, x.snapshot());
        status(200, c.head("customer/bookings/detail?bookingId=" + id));
        status(405, c.get("customer/bookings/pay?bookingId=" + id));
        status(405, c.head("customer/bookings/pay?bookingId=" + id));
        status(405, c.post("customer/bookings/detail", fields("_csrf", token(c.get("customer/home").body()))));
        assertEquals(confirmed, x.snapshot());
        System.out.println("HTTP actual page-two hold, explicit failure/new-key success, replay/current-state distinction, exact snapshots and GET/HEAD non-mutation: PASS");
        return id;
    }

    private void rolesAndOwnership(P6Fixture x, long other, long booking) throws Exception {
        var before = x.snapshot();
        for (long actor : new long[]{x.base.organizer, x.f.admin}) {
            String home = actor == x.f.admin ? "admin/home" : "organizer/home";
            HttpBrowser browser = login(x, actor, home);
            status(403, browser.get("customer/bookings"));
            status(403, browser.get("customer/bookings/detail?bookingId=" + booking));
            status(403, browser.post("customer/bookings/pay", fields("_csrf", token(browser.get(home).body()), "bookingId", "" + booking, "paymentKey", P6Fixture.key(), "outcome", "success")));
        }
        HttpBrowser outsider = login(x, other, "customer/home");
        var hidden = outsider.get("customer/bookings/detail?bookingId=" + booking); status(404, hidden);
        assertFalse(hidden.body().contains(x.booking(booking).code()));
        status(404, outsider.post("customer/bookings/pay", fields("_csrf", token(outsider.get("customer/home").body()), "bookingId", "" + booking, "paymentKey", P6Fixture.key(), "outcome", "success")));
        assertFalse(outsider.get("customer/bookings").body().contains(x.booking(booking).code()));
        HttpBrowser inactive = login(x, x.customer, "customer/home");
        String token = token(inactive.get("customer/home").body());
        x.f.db.changeAccount(x.f.email(x.customer), "CUSTOMER", false);
        inactive.redirect(inactive.post("customer/bookings/pay", fields("_csrf", token, "bookingId", "" + booking, "paymentKey", P6Fixture.key(), "outcome", "success")), "login");
        x.f.db.changeAccount(x.f.email(x.customer), "CUSTOMER", true);
        assertEquals(before, x.snapshot());
    }

    private void negativeForms(P6Fixture x, long id) throws Exception {
        HttpBrowser c = login(x, x.customer, "customer/home"), another = login(x, x.customer, "customer/home");
        var form = FinancialForms.form(c.get("customer/bookings/detail?bookingId=" + id + "&page=2&size=10&status=PENDING"), "pay");
        var before = x.snapshot();
        Map<String, String> valid = form.submit("success");
        for (String replacement : new String[]{"", "wrong", c.staleToken(), token(another.get("customer/home").body())}) {
            var bad = new LinkedHashMap<>(valid); if (replacement.isEmpty()) { bad.remove("_csrf"); } else { bad.put("_csrf", replacement); }
            status(403, c.post(form.action(), bad));
        }
        status(403, c.post(form.action(), valid, "_csrf", List.of(valid.get("_csrf"))));
        status(403, c.post(form.action() + "?bookingId=" + id, valid));
        for (String name : List.of("bookingId", "paymentKey", "outcome")) {
            status(400, c.post(form.action(), valid, name, List.of(valid.get(name))));
        }
        status(400, c.post("customer/bookings/expire", fields("_csrf", valid.get("_csrf"), "bookingId", "" + id), "bookingId", List.of("" + id)));
        for (var pair : Map.of("paymentKey", valid.get("paymentKey") + " ", "outcome", "REFUNDED", "size", "51", "page", "0").entrySet()) {
            var bad = new LinkedHashMap<>(valid); bad.put(pair.getKey(), pair.getValue());
            status(400, c.post(form.action(), bad));
        }
        var unsupported = new LinkedHashMap<>(valid); unsupported.put("outcome", "unknown");
        var invalid = c.post(form.action(), unsupported); status(400, invalid);
        var recovered = FinancialForms.form(invalid, "pay");
        assertEquals(form.fields().get("paymentKey"), recovered.fields().get("paymentKey"));
        assertEquals("2", recovered.fields().get("page")); assertEquals("10", recovered.fields().get("size")); assertEquals("PENDING", recovered.fields().get("status"));
        assertEquals(before, x.snapshot());
        logout(c, invalid);
        System.out.println("HTTP owner/role/live account, negative/duplicate CSRF, duplicate scalar, query POST, safe form recovery and validation logout: PASS");
    }

    private void sessionReplay(P6Fixture x, long paid) throws Exception {
        HttpBrowser c = login(x, x.customer, "customer/home");
        var replay = FinancialForms.form(c.get("customer/bookings/detail?bookingId=" + paid), "replay");
        var before = x.snapshot();
        Thread.sleep((Long.parseLong(System.getProperty("auth.expirySeconds", "5")) + 2) * 1000);
        status(403, c.post(replay.action(), replay.fields()));
        c.login(x.f.email(x.customer), P3Fixture.PASSWORD, "customer/home");
        var restored = new LinkedHashMap<>(replay.fields()); restored.put("_csrf", token(c.get("customer/home").body()));
        var result = follow(c, c.post(replay.action(), restored), paid);
        assertTrue(result.body().contains("Recorded attempt replayed"));
        assertEquals(before, x.snapshot());
    }

    private void history(P6Fixture x, long other, long paid) throws Exception {
        for (int i = 1; i < 11; i++) { x.hold(i); }
        long expired = x.f.access.read(c -> scalar(c, "SELECT MIN(id) FROM dbo.bookings WHERE customer_id=? AND event_id=? AND status='PENDING'", x.customer, x.event));
        x.deadline(expired, -1);
        x.f.access.write(c -> {
            update(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE id=?", x.event);
            update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", x.base.category); return null;
        });
        var before = x.snapshot();
        HttpBrowser c = login(x, x.customer, "customer/home");
        var page = c.get("customer/bookings?page=2&size=10"); status(200, page);
        assertTrue(page.body().contains("Page 2; 12 records"));
        assertTrue(page.body().contains(x.booking(paid).code()));
        String detail = link(page, x.booking(paid).code());
        assertTrue(detail.contains("page=2&size=10&status="));
        var owned = c.get(detail); status(200, owned);
        assertTrue(link(owned, "Return to My bookings").contains("page=2&size=10"));
        var expiredPage = c.get("customer/bookings?status=EXPIRED"); status(200, expiredPage);
        assertTrue(expiredPage.body().contains(x.booking(expired).code()));
        assertFalse(c.get("customer/bookings?status=PENDING").body().contains(x.booking(expired).code()));
        status(400, c.get("customer/bookings?status=UNKNOWN"));
        status(400, c.get("customer/bookings?page=1&page=1"));
        assertEquals(before, x.snapshot());
        var expiredDetail = c.get("customer/bookings/detail?bookingId=" + expired); status(200, expiredDetail);
        assertTrue(expiredDetail.body().contains("id=\"booking-state\">EXPIRED</dd>")); logout(c, expiredDetail);
        System.out.println("HTTP session expiry/relogin replay, owner-only effective history, non-first-page links, inactive-reference details and expired logout: PASS");
    }

    static HttpBrowser login(P6Fixture x, long actor, String home) throws Exception {
        HttpBrowser c = new HttpBrowser(); c.login(x.f.email(actor), P3Fixture.PASSWORD, home); return c;
    }
    static HttpResponse<String> follow(HttpBrowser c, HttpResponse<String> response, long id) throws Exception {
        status(303, response);
        String url = response.headers().firstValue("Location").orElseThrow();
        assertTrue(url.startsWith(c.path("customer/bookings/detail?bookingId=" + id + "&")));
        for (String forbidden : List.of("paymentKey", "_csrf", "amount", "returnUrl")) { assertFalse(url.contains(forbidden)); }
        var page = c.get(url); status(200, page); return page;
    }
    static String link(HttpResponse<String> response, String label) {
        Matcher m = Pattern.compile("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>" + Pattern.quote(label) + "</a>").matcher(response.body());
        assertTrue(m.find(), "Missing expected navigation link; body suppressed."); return FinancialForms.decode(m.group(1));
    }
    static void logout(HttpBrowser c, HttpResponse<String> page) throws Exception {
        c.redirect(c.post("logout", fields("_csrf", token(page.body()))), "");
        c.redirect(c.get("customer/bookings"), "login");
    }
}
