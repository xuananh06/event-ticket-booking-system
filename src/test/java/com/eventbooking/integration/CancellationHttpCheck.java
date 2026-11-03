package com.eventbooking.integration;

import org.junit.jupiter.api.Test;
import java.net.http.HttpResponse;
import java.util.*;
import static com.eventbooking.dao.JdbcAccess.*;
import static com.eventbooking.integration.HttpBrowser.*;
import static com.eventbooking.integration.PaymentHttpCheck.*;
import static org.junit.jupiter.api.Assertions.*;

class CancellationHttpCheck {
    @Test void deployedWholeBookingConfirmationFullRefundAndSafeRebooking() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        guest.redirect(guest.get("customer/bookings/cancel?bookingId=1"), "login");
        guest.redirect(guest.post("customer/bookings/cancel", fields("_csrf", token(guest.get("login").body()), "bookingId", "1", "confirm", "yes")), "login");
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            status(200, guest.get("health")); status(200, guest.get("login"));
            System.out.println("HTTP cancellation guest boundaries and unavailable foundation: PASS"); return;
        }
        try (P6Fixture x = new P6Fixture(3)) {
            long pending = x.hold(0), confirmed = x.hold(1), other = x.f.user("CUSTOMER");
            roles(x, other, pending);
            pending(x, pending);
            confirmedAndRebooking(x, other, confirmed);
            tooLate(x);
        }
    }

    private void roles(P6Fixture x, long other, long id) throws Exception {
        var before = x.snapshot();
        for (long actor : new long[]{x.base.organizer, x.f.admin}) {
            String home = actor == x.f.admin ? "admin/home" : "organizer/home";
            HttpBrowser c = login(x, actor, home);
            status(403, c.get("customer/bookings/cancel?bookingId=" + id));
            status(403, c.post("customer/bookings/cancel", fields("_csrf", token(c.get(home).body()), "bookingId", "" + id, "confirm", "yes")));
        }
        HttpBrowser outsider = login(x, other, "customer/home");
        var hidden = outsider.get("customer/bookings/cancel?bookingId=" + id); status(404, hidden);
        assertFalse(hidden.body().contains(x.booking(id).code()));
        status(404, outsider.post("customer/bookings/cancel", fields("_csrf", token(outsider.get("customer/home").body()), "bookingId", "" + id, "confirm", "yes")));
        assertEquals(before, x.snapshot());
    }

    private void pending(P6Fixture x, long id) throws Exception {
        HttpBrowser c = login(x, x.customer, "customer/home");
        var payment = FinancialForms.form(c.get("customer/bookings/detail?bookingId=" + id + "&page=2&size=10&status=PENDING"), "pay");
        var failed = follow(c, c.post(payment.action(), payment.submit("failure")), id);
        var before = x.snapshot();
        String url = link(failed, "Cancel whole booking");
        var page = c.get(url); status(200, page);
        assertTrue(page.body().contains("without a refund"));
        status(200, c.head(url)); assertEquals(before, x.snapshot());
        var form = FinancialForms.form(page, "cancel");
        assertEquals(c.path("customer/bookings/cancel"), form.action()); assertFalse(form.action().contains("?"));
        assertTrue(form.body().contains("name=\"confirm\" value=\"yes\""));
        Map<String, String> values = confirm(form);
        HttpBrowser second = login(x, x.customer, "customer/home");
        for (String replacement : new String[]{"", "wrong", c.staleToken(), token(second.get("customer/home").body())}) {
            var bad = new LinkedHashMap<>(values); if (replacement.isEmpty()) { bad.remove("_csrf"); } else { bad.put("_csrf", replacement); }
            status(403, c.post(form.action(), bad));
        }
        status(403, c.post(form.action(), values, "_csrf", List.of(values.get("_csrf"))));
        status(403, c.post(form.action() + "?bookingId=" + id, values));
        for (String name : List.of("confirm", "bookingId")) { status(400, c.post(form.action(), values, name, List.of(values.get(name)))); }
        var missing = new LinkedHashMap<>(form.fields());
        var invalid = c.post(form.action(), missing); status(400, invalid);
        var recovered = FinancialForms.form(invalid, "cancel");
        assertEquals("2", recovered.fields().get("page")); assertEquals("10", recovered.fields().get("size")); assertEquals("PENDING", recovered.fields().get("status"));
        var wrong = confirm(recovered); wrong.put("confirm", "true"); status(400, c.post(recovered.action(), wrong));
        assertEquals(before, x.snapshot());
        var items = x.items(id);
        var result = follow(c, c.post(recovered.action(), confirm(recovered)), id);
        assertTrue(result.body().contains("id=\"booking-state\">CANCELLED</dd>"));
        assertTrue(result.body().contains("This booking is cancelled. Its history remains available."));
        assertEquals("CANCELLED", x.booking(id).status()); assertNull(x.booking(id).confirmedAt());
        assertEquals(items, x.items(id)); assertEquals(1, x.attempts(id)); assertEquals(0, x.successes(id));
        var after = x.snapshot();
        follow(c, c.post(form.action(), values), id);
        var replayed = follow(c, c.post(payment.action(), payment.submit("failure")), id);
        assertTrue(replayed.body().contains("Current attempt state: <strong>FAILED</strong>"));
        assertTrue(replayed.body().contains("Current booking state: <strong>CANCELLED</strong>")); assertEquals(after, x.snapshot());
        logout(c, replayed);
        System.out.println("HTTP pending confirmation GET/HEAD, explicit body-only cancellation, negative/duplicate CSRF/scalars, 400 context recovery, whole release, retained failure replay and cancelled logout: PASS");
    }

    private void confirmedAndRebooking(P6Fixture x, long other, long id) throws Exception {
        HttpBrowser c = login(x, x.customer, "customer/home");
        var payment = FinancialForms.form(c.get("customer/bookings/detail?bookingId=" + id), "pay");
        follow(c, c.post(payment.action(), payment.submit("success")), id);
        var original = x.booking(id); var items = x.items(id);
        x.deadline(id, -1);
        var details = c.get("customer/bookings/detail?bookingId=" + id); status(200, details);
        var before = x.snapshot();
        var page = c.get(link(details, "Cancel whole booking")); status(200, page);
        assertTrue(page.body().contains("10.00 VND</strong>")); assertEquals(before, x.snapshot());
        var form = FinancialForms.form(page, "cancel"); var values = confirm(form);
        values.putAll(fields("refund_amount", "999999", "amount", "0", "customer_id", "1", "booking_status", "CONFIRMED", "refunded_at", "9999-12-31"));
        var result = follow(c, c.post(form.action(), values), id);
        assertTrue(result.body().contains("REFUNDED")); assertTrue(result.body().contains("CANCELLED"));
        assertEquals(original.confirmedAt(), x.booking(id).confirmedAt()); assertEquals(items, x.items(id));
        assertEquals(1, x.successes(id)); assertEquals(1, x.attempts(id));
        assertEquals(1, x.f.access.read(conn -> scalar(conn, "SELECT COUNT_BIG(*) FROM dbo.payments WHERE booking_id=? AND status='REFUNDED' AND amount=10 AND succeeded_at=? AND refunded_at IS NOT NULL", id, original.confirmedAt())).longValue());
        HttpBrowser newer = login(x, other, "customer/home");
        var hold = FinancialForms.form(newer.get("customer/events/seats?eventId=" + x.event), "hold");
        var selection = new LinkedHashMap<>(hold.fields()); selection.put("eventSeatId", x.ids.get(1).toString());
        var held = newer.post(hold.action(), selection); status(303, held);
        String newCheckout = held.headers().firstValue("Location").orElseThrow(); status(200, newer.get(newCheckout));
        long current = Long.parseLong(newCheckout.substring(newCheckout.indexOf("bookingId=") + 10));
        var after = x.snapshot();
        String currentToken = token(c.get("customer/bookings/detail?bookingId=" + id).body());
        var replayValues = payment.submit("success"); replayValues.put("_csrf", currentToken);
        var replayed = follow(c, c.post(payment.action(), replayValues), id);
        assertTrue(replayed.body().contains("Original outcome: <strong>success</strong>"));
        assertTrue(replayed.body().contains("Current attempt state: <strong>REFUNDED</strong>"));
        assertTrue(replayed.body().contains("Current booking state: <strong>CANCELLED</strong>"));
        values.put("_csrf", currentToken); follow(c, c.post(form.action(), values), id);
        assertEquals(after, x.snapshot());
        assertEquals(current, x.f.access.read(conn -> scalar(conn, "SELECT current_booking_id FROM dbo.event_seats WHERE id=?", x.ids.get(1))).longValue());
        var changed = new LinkedHashMap<>(replayValues); changed.put("outcome", "failure");
        var conflict = c.post(payment.action(), changed); status(409, conflict); assertEquals(after, x.snapshot()); logout(c, conflict);
        System.out.println("HTTP confirmed cancellation after old hold expiry, full immutable refund, actual new-owner hold, replay/repeat safety and conflict logout: PASS");
    }

    private void tooLate(P6Fixture x) throws Exception {
        long id = x.hold(2);
        HttpBrowser c = login(x, x.customer, "customer/home");
        var form = FinancialForms.form(c.get("customer/bookings/cancel?bookingId=" + id), "cancel");
        x.f.access.write(conn -> { update(conn, "UPDATE dbo.events SET starts_at=SYSUTCDATETIME() WHERE id=?", x.event); return null; });
        var before = x.snapshot();
        var rejected = c.post(form.action(), confirm(form)); status(409, rejected); assertEquals(before, x.snapshot());
        assertFalse(rejected.body().contains("name=\"action\" value=\"cancel\"")); logout(c, rejected);
        System.out.println("HTTP event-start cancellation rejection is atomic and preserves working logout: PASS");
    }

    private static Map<String, String> confirm(FinancialForms.Form form) {
        assertTrue(form.body().contains("name=\"confirm\" value=\"yes\""));
        var values = new LinkedHashMap<>(form.fields()); values.put("confirm", "yes"); return values;
    }
}
