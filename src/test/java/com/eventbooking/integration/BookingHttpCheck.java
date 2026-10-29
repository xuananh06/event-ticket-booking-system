package com.eventbooking.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

import static com.eventbooking.dao.JdbcAccess.*;
import static com.eventbooking.integration.HttpBrowser.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingHttpCheck {
    @Test void deployedCustomerSelectionHoldAndOwnedCheckout() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        guest.redirect(guest.get("customer/events/seats?eventId=1"), "login");
        guest.redirect(guest.get("customer/bookings/checkout?bookingId=1"), "login");
        guest.redirect(guest.post("customer/bookings/hold", fields("_csrf", token(guest.get("register").body()), "eventId", "1", "eventSeatId", "1")), "login");
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            status(503, guest.get("events/seats?eventId=1"));
            status(200, guest.get("login")); status(200, guest.get("health"));
            System.out.println("HTTP booking guest boundaries and unavailable database foundation: PASS");
            return;
        }
        try (P5Fixture x = new P5Fixture(12)) {
            long other = x.f.user("CUSTOMER");
            x.f.access.write(c -> {
                // Explicit Unicode/XSS fixture verifies UTF-8 fragment and snapshot rendering.
                update(c, "UPDATE dbo.seats SET row_label=? WHERE id=?", "H\u00e0<&", x.f.seats.get(11));
                update(c, "UPDATE dbo.events SET title=? WHERE id=?", x.f.prefix + "<b>Customer event</b>", x.event);
                return null;
            });
            status(200, guest.get("events/seats?eventId=" + x.event));
            assertTrue(guest.get("events/seats?eventId=" + x.event).body().contains("Sign in to reserve seats"));
            var catalog = guest.get("events?q=" + java.net.URLEncoder.encode(x.f.prefix, java.nio.charset.StandardCharsets.UTF_8));
            status(200, catalog);
            assertTrue(catalog.body().contains("Page 1 \u00b7"));
            assertFalse(catalog.body().contains("\u00c2\u00b7"));
            roles(x);
            long booking = validationAndHold(x);
            securityAndOwnership(x, other, booking);
            sessionExpiryDoesNotRenew(x, booking);
            expiryAndTakeover(x, other, booking);
            validationLogout(x);
        }
    }

    private long validationAndHold(P5Fixture x) throws Exception {
        HttpBrowser customer = login(x, x.customer, "customer/home");
        var publicPage = customer.get("events/seats?eventId=" + x.event);
        assertTrue(publicPage.body().contains(customer.path("customer/events/seats") + "?eventId=" + x.event));
        String route = selection(x);
        var page = customer.get(route); status(200, page);
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().contains("UTF-8"));
        assertTrue(page.body().contains("H\u00e0&lt;&amp;"));
        assertFalse(page.body().contains("id=\"select-" + x.ids.get(0) + "\""));
        long target = x.ids.get(11);
        RenderedForm form = rendered(page, "hold");
        assertEquals("2", form.fields().get("page")); assertEquals("10", form.fields().get("size"));
        assertEquals(customer.path("customer/bookings/hold"), form.action());
        String unchanged = digest(x);
        var invalid = customer.post(form.action(), form.fields(), "eventSeatId", List.of("" + target, "0" + target));
        status(400, invalid);
        assertTrue(invalid.body().contains("Page 2 &middot;"));
        assertFalse(invalid.body().contains("id=\"select-" + x.ids.get(0) + "\""));
        assertChecked(invalid.body(), target);
        RenderedForm corrected = rendered(invalid, "hold");
        assertEquals("2", corrected.fields().get("page")); assertEquals("10", corrected.fields().get("size"));
        assertEquals(unchanged, digest(x)); assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items"));
        for (String[] malformed : new String[][]{{"page", "0"}, {"page", "9223372036854775808"}, {"size", "51"}}) {
            Map<String, String> bad = new LinkedHashMap<>(corrected.fields()); bad.put(malformed[0], malformed[1]);
            status(400, customer.post(corrected.action(), bad, "eventSeatId", List.of("" + target)));
            assertEquals(unchanged, digest(x));
        }
        Map<String, String> values = new LinkedHashMap<>(corrected.fields());
        values.putAll(fields("price", "0", "total_amount", "0", "customer_id", "1", "status", "CONFIRMED", "booking_code", "forged", "expires_at", "9999-12-31"));
        List<String> checked = checked(corrected.body());
        assertEquals(List.of("" + target), checked);
        long id = bookingRedirect(customer, customer.post(corrected.action(), values, "eventSeatId", checked));
        var summary = x.service().checkout(x.customer, id);
        assertEquals("PENDING", summary.getState()); assertEquals(new BigDecimal("10.00"), summary.booking().total());
        assertNotEquals("forged", summary.booking().code());
        assertEquals(Duration.ofMinutes(5), Duration.between(summary.booking().createdAt(), summary.booking().expiresAt()));
        assertEquals(List.of(target), summary.items().stream().map(com.eventbooking.model.BookingItem::eventSeatId).toList());
        assertEquals(1, x.count("bookings")); assertEquals(1, x.count("booking_items")); BookingHoldIT.available(x, 11);
        var checkout = customer.get("customer/bookings/checkout?bookingId=" + id); status(200, checkout);
        assertTrue(checkout.body().contains("Row H\u00e0&lt;&amp; / 12"));
        assertTrue(checkout.body().contains("&lt;b&gt;Customer event&lt;/b&gt;"));
        assertFalse(checkout.body().contains("<b>Customer event</b>"));
        assertTrue(checkout.body().contains("Payment is not available yet."));
        assertTrue(checkout.body().contains(summary.booking().getExpiresUtc()));
        String after = digest(x);
        status(409, customer.post(form.action(), form.fields(), "eventSeatId", checked));
        assertEquals(after, digest(x));
        assertEquals(summary.booking(), x.service().checkout(x.customer, id).booking());
        status(405, customer.get("customer/bookings/hold?eventId=" + x.event));
        status(405, customer.get("customer/bookings/expire?bookingId=" + id));
        status(405, customer.head("customer/bookings/hold?eventId=" + x.event));
        var head = customer.head("customer/bookings/checkout?bookingId=" + id);
        status(200, head); assertTrue(head.body().isEmpty());
        assertEquals(after, digest(x));
        System.out.println("HTTP rendered page-two selection/400 retention/corrected hold, exact server fields, escaped UTF-8 checkout, repeated hold and GET non-mutation: PASS");
        return id;
    }

    private void securityAndOwnership(P5Fixture x, long other, long booking) throws Exception {
        HttpBrowser customer = login(x, x.customer, "customer/home");
        RenderedForm form = rendered(customer.get(selection(x)), "hold");
        String before = digest(x);
        rejectTokens(customer, form, List.of(x.ids.get(10).toString()));
        status(403, customer.post(form.action() + "?page=2", form.fields(), "eventSeatId", List.of(x.ids.get(10).toString())));
        status(400, customer.post(form.action(), form.fields()));
        status(400, customer.post(form.action(), form.fields(), "eventSeatId", List.of(x.ids.get(10).toString(), "9223372036854775808")));
        long foreignEvent = x.f.event(x.organizer, x.category, x.venue, x.f.prefix + "Foreign", "PUBLISHED", 40);
        x.f.inventory(foreignEvent, x.f.seats.get(0), x.venue);
        long foreign = x.f.access.read(c -> scalar(c, "SELECT id FROM dbo.event_seats WHERE event_id=?", foreignEvent));
        status(409, customer.post(form.action(), form.fields(), "eventSeatId", List.of(x.ids.get(10).toString(), "" + foreign)));
        assertEquals(before, digest(x)); assertEquals(1, x.count("bookings")); assertEquals(1, x.count("booking_items"));
        HttpBrowser outsider = login(x, other, "customer/home");
        var hidden = outsider.get("customer/bookings/checkout?bookingId=" + booking); status(404, hidden);
        assertFalse(hidden.body().contains(x.service().checkout(x.customer, booking).booking().code()));
        var outsiderForm = rendered(outsider.get(selection(x)), "hold");
        status(404, outsider.post("customer/bookings/expire", fields("_csrf", outsiderForm.fields().get("_csrf"), "bookingId", "" + booking)));
        var ownCheckout = customer.get("customer/bookings/checkout?bookingId=" + booking); status(200, ownCheckout);
        status(409, customer.post("customer/bookings/expire", fields("_csrf", token(ownCheckout.body()), "bookingId", "" + booking)));
        assertEquals(before, digest(x));
        System.out.println("HTTP booking ownership, live roles, missing/wrong/cross-session/stale CSRF, query POST rejection, mixed-ID rollback and early expiry denial: PASS");
    }

    private void roles(P5Fixture x) throws Exception {
        for (long actor : new long[]{x.organizer, x.f.admin}) {
            String home = actor == x.organizer ? "organizer/home" : "admin/home";
            HttpBrowser browser = login(x, actor, home);
            for (String path : List.of("customer/events/seats?eventId=" + x.event, "customer/bookings/checkout?bookingId=1")) { status(403, browser.get(path)); }
            String csrf = token(browser.get(home).body());
            status(403, browser.post("customer/bookings/hold", fields("_csrf", csrf, "eventId", "" + x.event, "eventSeatId", x.ids.get(0).toString())));
            status(403, browser.post("customer/bookings/expire", fields("_csrf", csrf, "bookingId", "1")));
            assertFalse(browser.get("events/seats?eventId=" + x.event).body().contains(">Select seats</a>"));
        }
    }

    private void sessionExpiryDoesNotRenew(P5Fixture x, long id) throws Exception {
        HttpBrowser customer = login(x, x.customer, "customer/home");
        var original = x.service().checkout(x.customer, id).booking();
        status(200, customer.get("customer/bookings/checkout?bookingId=" + id));
        Thread.sleep((Long.parseLong(System.getProperty("auth.expirySeconds", "5")) + 2) * 1000);
        customer.redirect(customer.get("customer/bookings/checkout?bookingId=" + id), "login");
        customer.login(x.f.email(x.customer), P3Fixture.PASSWORD, "customer/home");
        status(200, customer.get("customer/bookings/checkout?bookingId=" + id));
        assertEquals(original, x.service().checkout(x.customer, id).booking());
        System.out.println("HTTP actual session expiry/relogin retains the exact original booking and deadline: PASS");
    }

    private void expiryAndTakeover(P5Fixture x, long other, long old) throws Exception {
        x.deadline(old, -1);
        HttpBrowser owner = login(x, x.customer, "customer/home");
        String checkoutRoute = "customer/bookings/checkout?bookingId=" + old;
        var expired = owner.get(checkoutRoute); status(200, expired);
        assertTrue(expired.body().contains("id=\"booking-state\">EXPIRED</dd>"));
        RenderedForm expiration = rendered(expired, "expire");
        String before = digest(x);
        status(200, owner.get(checkoutRoute)); assertEquals(before, digest(x));
        assertEquals("PENDING", x.service().checkout(x.customer, old).booking().status());
        rejectTokens(owner, expiration, List.of());
        status(403, owner.post(expiration.action() + "?bookingId=" + old, expiration.fields()));
        assertEquals(before, digest(x));
        HttpBrowser second = login(x, other, "customer/home");
        RenderedForm choose = rendered(second.get(selection(x)), "hold");
        long current = bookingRedirect(second, second.post(choose.action(), choose.fields(), "eventSeatId", List.of(x.ids.get(11).toString())));
        var retained = x.service().checkout(other, current).booking();
        var history = x.service().checkout(x.customer, old).items();
        expiration = rendered(owner.get(checkoutRoute), "expire");
        owner.redirect(owner.post(expiration.action(), expiration.fields()), checkoutRoute);
        owner.redirect(owner.post(expiration.action(), expiration.fields()), checkoutRoute);
        assertEquals("EXPIRED", x.service().checkout(x.customer, old).booking().status());
        assertEquals(history, x.service().checkout(x.customer, old).items());
        assertEquals(retained, x.service().checkout(other, current).booking());
        assertEquals(1L, x.f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE id=? AND current_booking_id=? AND hold_expires_at=? AND state='HELD'", x.ids.get(11), current, retained.expiresAt())).longValue());
        logout(owner, owner.get(checkoutRoute));
        owner.redirect(owner.get(checkoutRoute), "login");
        assertEquals(retained, x.service().checkout(other, current).booking());
        System.out.println("HTTP read-only effective expiry, owner-only rendered normalization, negative CSRF, expired takeover, preserved new hold/history and expired-page logout: PASS");
    }

    private void validationLogout(P5Fixture x) throws Exception {
        HttpBrowser customer = login(x, x.customer, "customer/home");
        RenderedForm form = rendered(customer.get(selection(x)), "hold");
        String before = digest(x);
        var invalid = customer.post(form.action(), form.fields(), "eventSeatId", List.of(x.ids.get(10).toString(), x.ids.get(10).toString()));
        status(400, invalid); assertChecked(invalid.body(), x.ids.get(10));
        logout(customer, invalid);
        customer.redirect(customer.get(selection(x)), "login");
        assertEquals(before, digest(x));
    }

    private void rejectTokens(HttpBrowser browser, RenderedForm form, List<String> seats) throws Exception {
        for (String csrf : new String[]{null, "x".repeat(43), token(new HttpBrowser().get("register").body()), browser.staleToken()}) {
            Map<String, String> values = new LinkedHashMap<>(form.fields());
            if (csrf == null) { values.remove("_csrf"); } else { values.put("_csrf", csrf); }
            status(403, browser.post(form.action(), values, "eventSeatId", seats));
        }
    }

    private static HttpBrowser login(P5Fixture x, long user, String home) throws Exception {
        HttpBrowser browser = new HttpBrowser(); browser.login(x.f.email(user), P3Fixture.PASSWORD, home); return browser;
    }

    private static String selection(P5Fixture x) { return "customer/events/seats?eventId=" + x.event + "&page=2&size=10"; }

    private static String digest(P5Fixture x) {
        return x.f.access.read(c -> {
            try (var s = statement(c, "SELECT CONVERT(varchar(64),HASHBYTES('SHA2_256',(SELECT id,price,state,current_booking_id,hold_expires_at FROM dbo.event_seats WHERE event_id=? ORDER BY id FOR JSON PATH,INCLUDE_NULL_VALUES)),2)", x.event); var r = s.executeQuery()) {
                assertTrue(r.next()); return r.getString(1);
            }
        });
    }

    private record RenderedForm(String action, Map<String, String> fields, String body) { }

    private static RenderedForm rendered(HttpResponse<String> response, String action) {
        Matcher forms = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>").matcher(response.body());
        while (forms.find()) {
            if (!"post".equals(attribute(forms.group(1), "method")) || !forms.group(2).contains("name=\"action\" value=\"" + action + "\"")) { continue; }
            Map<String, String> values = new LinkedHashMap<>();
            Matcher inputs = Pattern.compile("<input\\b[^>]*>").matcher(forms.group(2));
            while (inputs.find()) {
                if ("hidden".equals(attribute(inputs.group(), "type"))) { values.put(attribute(inputs.group(), "name"), attribute(inputs.group(), "value")); }
            }
            String url = attribute(forms.group(1), "action").replace("&amp;", "&");
            assertNull(URI.create(url).getRawQuery(), "Mutation form actions must not carry query parameters.");
            assertNotNull(values.get("_csrf"));
            return new RenderedForm(url, values, forms.group(2));
        }
        throw new AssertionError("Missing rendered booking form; body suppressed.");
    }

    private static String attribute(String tag, String name) {
        Matcher found = Pattern.compile("\\b" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return found.find() ? found.group(1) : null;
    }

    private static List<String> checked(String body) {
        List<String> ids = new ArrayList<>();
        Matcher inputs = Pattern.compile("<input\\b[^>]*>").matcher(body);
        while (inputs.find()) {
            if ("eventSeatId".equals(attribute(inputs.group(), "name")) && Pattern.compile("\\schecked(?:\\s|>)").matcher(inputs.group()).find()) { ids.add(attribute(inputs.group(), "value")); }
        }
        return ids;
    }

    private static void assertChecked(String body, long id) { assertTrue(checked(body).contains("" + id), "Selected visible seat must remain checked."); }

    private static long bookingRedirect(HttpBrowser browser, HttpResponse<String> response) {
        status(303, response);
        String location = response.headers().firstValue("Location").orElseThrow();
        Matcher id = Pattern.compile("^" + Pattern.quote(browser.path("customer/bookings/checkout")) + "\\?bookingId=([1-9][0-9]*)$").matcher(location);
        assertTrue(id.matches(), "Expected fixed owned checkout redirect; location suppressed.");
        return Long.parseLong(id.group(1));
    }

    private static void logout(HttpBrowser browser, HttpResponse<String> response) throws Exception {
        Matcher forms = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>").matcher(response.body());
        while (forms.find()) {
            String action = attribute(forms.group(1), "action");
            if (action != null && action.endsWith("/logout")) {
                browser.redirect(browser.post(action, fields("_csrf", token(forms.group(2)))), ""); return;
            }
        }
        throw new AssertionError("Missing rendered logout form; body suppressed.");
    }
}
