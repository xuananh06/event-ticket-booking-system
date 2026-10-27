package com.eventbooking.integration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.eventbooking.integration.HttpBrowser.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryHttpCheck {
    @Test void deployedInventorySetupAndAvailability() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        guest.redirect(guest.get("organizer/events/inventory?eventId=1"), "login");
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            status(503, guest.get("events/seats?eventId=1"));
            status(200, guest.get(""));
            System.out.println("HTTP inventory guest protection without available database: PASS");
            return;
        }
        try (P4Fixture fixture = new P4Fixture()) {
            P3Fixture f = fixture.f;
            long actor = f.user("ORGANIZER"), other = f.user("ORGANIZER"), customer = f.user("CUSTOMER");
            long category = f.category(), venue = f.venue();
            f.seat(venue);
            // Dedicated Unicode/XSS fixture with a sparse physical number.
            long sparse = f.references.saveSeat(f.admin, null, venue, "<b>Ω</b>", Integer.MAX_VALUE, true); f.seats.add(sparse);
            long event = f.event(actor, category, venue, f.prefix + "HTTP <b>Inventory</b>", "DRAFT", 40);
            HttpBrowser owner = new HttpBrowser(); owner.login(f.email(actor), P3Fixture.PASSWORD, "organizer/home");
            HttpBrowser outsider = new HttpBrowser();
            String route = "organizer/events/inventory?eventId=" + event;
            for (long account : new long[]{f.admin, customer}) {
                HttpBrowser denied = new HttpBrowser(); denied.login(f.email(account), P3Fixture.PASSWORD, account == f.admin ? "admin/home" : "customer/home");
                status(403, denied.get(route));
                status(403, denied.post("organizer/events/inventory", fields("_csrf", token(denied.get(account == f.admin ? "admin/home" : "customer/home").body()), "action", "generate", "eventId", "" + event, "price", "1")));
            }
            var page = owner.get(route); status(200, page); assertTrue(page.body().contains("&lt;b&gt;Inventory&lt;/b&gt;"));
            Form generate = form(page, "generate", null);
            var values = generate.fields("action", "generate", "eventId", "" + event, "price", "123.45", "status", "PUBLISHED", "organizer_id", "" + other, "venue_id", "1");
            rejectTokens(owner, generate, values);
            status(403, owner.post(generate.action() + "?eventId=" + event, values));
            var invalid = new LinkedHashMap<>(values); invalid.put("price", "1.001");
            var invalidPage = owner.post(generate.action(), invalid); status(400, invalidPage); assertTrue(invalidPage.body().contains("value=\"1.001\""));
            status(200, owner.get(route + "&action=generate&price=10"));
            assertEquals(0, InventorySetupIT.rows(f, event).total());
            owner.redirect(owner.post(generate.action(), values), route);
            assertEquals(2, InventorySetupIT.rows(f, event).total());
            assertEquals("DRAFT", new com.eventbooking.service.EventService(f.access, new com.eventbooking.dao.EventDao()).owned(actor, event).status());
            var repeated = new LinkedHashMap<>(values); repeated.put("price", "999");
            owner.redirect(owner.post(generate.action(), repeated), route);
            var unchanged = owner.get(route); status(200, unchanged);
            assertTrue(unchanged.body().contains("Inventory is already up to date. Existing prices were preserved."));
            assertTrue(InventorySetupIT.rows(f, event).items().stream().allMatch(row -> row.price().equals(new BigDecimal("123.45"))));
            page = owner.get(route); status(200, page);
            assertTrue(page.body().contains("&lt;b&gt;Ω&lt;/b&gt;")); assertTrue(page.body().contains("2147483647"));
            var rows = InventorySetupIT.rows(f, event).items();
            Form individual = form(page, "price", "eventSeatId", rows.get(0).id());
            owner.redirect(owner.post(individual.action(), individual.fields("action", "price", "eventId", "" + event, "eventSeatId", "" + rows.get(0).id(), "price", "7.50")), route);
            assertEquals(new BigDecimal("7.50"), InventorySetupIT.rows(f, event).items().get(0).price());
            assertEquals(new BigDecimal("123.45"), InventorySetupIT.rows(f, event).items().get(1).price());
            Form price = form(page, "price", null);
            for (var row : rows) { assertTrue(page.body().contains("name=\"eventSeatId\" value=\"" + row.id() + "\"")); }
            var pricing = price.fields("action", "price", "eventId", "" + event, "price", "0");
            var onePrice = new LinkedHashMap<>(pricing); onePrice.put("eventSeatId", "" + rows.get(0).id());
            rejectTokens(owner, price, onePrice);
            owner.redirect(owner.post(price.action(), pricing, "eventSeatId", rows.stream().map(row -> "" + row.id()).toList()), route);
            assertTrue(InventorySetupIT.rows(f, event).items().stream().allMatch(row -> row.price().equals(new BigDecimal("0.00"))));
            status(400, owner.post(price.action(), pricing));
            status(400, owner.post(price.action(), pricing, "eventSeatId", List.of("" + rows.get(0).id(), "" + rows.get(0).id())));
            long another = f.event(actor, category, venue, f.prefix + "Other", "DRAFT", 40);
            InventorySetupIT.service(f).generate(actor, another, "25");
            long foreign = InventorySetupIT.rows(f, another).items().get(0).id();
            var changed = new LinkedHashMap<>(pricing); changed.put("price", "100");
            status(404, owner.post(price.action(), changed, "eventSeatId", List.of("" + rows.get(0).id(), "" + foreign)));
            assertTrue(InventorySetupIT.rows(f, event).items().stream().allMatch(row -> row.price().equals(new BigDecimal("0.00"))));
            outsider.login(f.email(other), P3Fixture.PASSWORD, "organizer/home");
            status(404, outsider.get(route));
            Form otherForm = form(outsider.get("organizer/events/inventory?eventId=" + f.event(other, category, venue, f.prefix + "Outsider", "DRAFT", 40)), "generate", null);
            status(404, outsider.post(otherForm.action(), otherForm.fields("action", "generate", "eventId", "" + event, "price", "20")));
            status(404, outsider.post(otherForm.action(), otherForm.fields("action", "price", "eventId", "" + event, "eventSeatId", "" + rows.get(0).id(), "price", "20")));
            status(404, outsider.post(otherForm.action(), otherForm.fields("action", "publish", "confirmation", "publish", "eventId", "" + event)));
            var paged = owner.get(route + "&size=1"); status(200, paged); assertTrue(paged.body().contains("Next</a>"));
            assertTrue(paged.body().contains("eventId=" + event + "&amp;page=2&amp;size=1"));
            status(400, owner.get(route + "&size=51"));
            status(404, guest.get("events/detail?id=" + event));
            status(404, guest.get("events/seats?eventId=" + event));
            Form publish = form(owner.get(route), "publish", null);
            var publishing = publish.fields("action", "publish", "eventId", "" + event, "confirmation", "publish", "status", "CANCELLED");
            rejectTokens(owner, publish, publishing);
            status(400, owner.post(publish.action(), publish.fields("action", "publish", "eventId", "" + event)));
            status(200, owner.get(route + "&action=publish"));
            assertEquals("DRAFT", new com.eventbooking.service.EventService(f.access, new com.eventbooking.dao.EventDao()).owned(actor, event).status());
            owner.redirect(owner.post(publish.action(), publishing), route);
            status(200, guest.get("events/detail?id=" + event));
            var availability = guest.get("events/seats?eventId=" + event); status(200, availability);
            assertTrue(availability.body().contains("0.00 - 0.00 VND"));
            assertFalse(availability.body().contains("name=\"eventSeatId\""));
            var readOnly = owner.get(route); status(200, readOnly); assertFalse(readOnly.body().contains("Generate missing seats</button>"));
            status(409, owner.post(generate.action(), values));
            status(409, owner.post(price.action(), pricing, "eventSeatId", List.of("" + rows.get(0).id())));
            logoutFrom(owner, owner.post(publish.action(), publishing), route, 409);
            HttpBrowser validation = new HttpBrowser(); validation.login(f.email(actor), P3Fixture.PASSWORD, "organizer/home");
            Form anotherForm = form(validation.get("organizer/events/inventory?eventId=" + another), "generate", null);
            logoutFrom(validation, validation.post(anotherForm.action(), anotherForm.fields("action", "generate", "eventId", "" + another, "price", "NaN")), route, 400);
            System.out.println("HTTP rendered inventory generation/pricing, exact values, mixed batches, roles/ownership, CSRF, sparse labels, paging and read-only status: PASS");
            System.out.println("HTTP guarded publication, before/after visibility, immutable setup and rendered error/validation logout: PASS");
            publicAvailability(fixture);
            pricingValidationPaging(fixture);
        }
    }

    private void pricingValidationPaging(P4Fixture fixture) throws Exception {
        P3Fixture f = fixture.f;
        long actor = f.user("ORGANIZER"), venue = f.venue(), category = f.category();
        for (int i = 0; i < 12; i++) { f.seat(venue); }
        long event = f.event(actor, category, venue, f.prefix + "Paged pricing", "DRAFT", 30);
        InventorySetupIT.service(f).generate(actor, event, "10");
        var seats = InventorySetupIT.rows(f, event).items();
        HttpBrowser owner = new HttpBrowser(); owner.login(f.email(actor), P3Fixture.PASSWORD, "organizer/home");
        String route = "organizer/events/inventory?eventId=" + event;
        for (boolean individual : new boolean[]{false, true}) {
            int size = individual ? 6 : 10;
            long selected = seats.get(individual ? 11 : 10).id();
            var page = owner.get(route + "&page=2&size=" + size); status(200, page);
            assertFalse(page.body().contains("id=\"select-" + seats.get(0).id() + "\""));
            assertTrue(page.body().contains("id=\"select-" + selected + "\""));
            PricingForm submitted = pricingForm(page.body(), individual ? selected : null);
            var fields = new LinkedHashMap<>(submitted.fields());
            fields.put("eventSeatId", "" + selected); fields.put("price", "1.001");
            var original = prices(f, event);
            var invalid = owner.post(submitted.action(), fields); status(400, invalid);
            assertEquals(original, prices(f, event), "Rejected pricing must preserve every inventory price.");
            assertTrue(invalid.body().contains("Page 2 &middot;"), "Validation must retain the non-first inventory page.");
            assertFalse(invalid.body().contains("id=\"select-" + seats.get(0).id() + "\""));
            var checkbox = java.util.regex.Pattern.compile("<input\\b[^>]*id=\"select-" + selected + "\"[^>]*>").matcher(invalid.body());
            assertTrue(checkbox.find(), "The selected second-page seat must remain visible.");
            assertTrue(checkbox.group().contains(" checked"), "The visible seat selection must remain checked.");
            String priceId = individual ? "price-" + selected : "batch-price";
            var input = java.util.regex.Pattern.compile("<input\\b[^>]*id=\"" + priceId + "\"[^>]*>").matcher(invalid.body());
            assertTrue(input.find()); assertEquals("1.001", attribute(input.group(), "value"));
            var unsafeFields = new LinkedHashMap<>(fields); unsafeFields.put("price", "1.001<script>price</script>");
            var escaped = owner.post(submitted.action(), unsafeFields); status(400, escaped);
            var escapedInput = input.pattern().matcher(escaped.body()); assertTrue(escapedInput.find());
            assertTrue(attribute(escapedInput.group(), "value").contains("&lt;script&gt;price&lt;/script&gt;"));
            assertFalse(escaped.body().contains("<script>price</script>"));
            assertEquals(original, prices(f, event));
            PricingForm corrected = pricingForm(invalid.body(), individual ? selected : null);
            assertEquals("2", corrected.fields().get("page"));
            assertEquals("" + size, corrected.fields().get("size"));
            var correctedFields = new LinkedHashMap<>(corrected.fields());
            String amount = individual ? "12.50" : "11.25"; correctedFields.put("price", amount);
            if (individual) {
                assertEquals("" + selected, correctedFields.get("eventSeatId"));
                owner.redirect(owner.post(corrected.action(), correctedFields), route);
            } else {
                var selectedValues = new java.util.ArrayList<String>();
                var checked = java.util.regex.Pattern.compile("<input\\b[^>]*form=\"batch-pricing\"[^>]*>").matcher(invalid.body());
                while (checked.find()) { if (checked.group().contains(" checked")) { selectedValues.add(attribute(checked.group(), "value")); } }
                assertEquals(List.of("" + selected), selectedValues);
                owner.redirect(owner.post(corrected.action(), correctedFields, "eventSeatId", selectedValues), route);
            }
            var expected = new LinkedHashMap<>(original); expected.put(selected, new BigDecimal(amount));
            assertEquals(expected, prices(f, event), "Corrected pricing must change only the intended inventory seat.");
        }
        var page = owner.get(route + "&page=2&size=10"); status(200, page);
        PricingForm batch = pricingForm(page.body(), null);
        var valid = new LinkedHashMap<>(batch.fields()); valid.put("price", "88.00"); valid.put("eventSeatId", "" + seats.get(10).id());
        var before = prices(f, event);
        for (String[] invalid : new String[][]{{"page", "0"}, {"page", "1000001"}, {"page", "9223372036854775808"}, {"size", "0"}, {"size", "51"}, {"size", "invalid"}}) {
            var malformed = new LinkedHashMap<>(valid); malformed.put(invalid[0], invalid[1]);
            status(400, owner.post(batch.action(), malformed));
            assertEquals(before, prices(f, event), "Invalid paging must be rejected before writing prices.");
        }
        status(403, owner.post(batch.action() + "?page=2&size=10", valid));
        rejectTokens(owner, new Form(batch.action(), batch.fields().get("_csrf")), valid);
        assertEquals(before, prices(f, event));
        var invalidPrice = new LinkedHashMap<>(valid); invalidPrice.put("price", "1.001");
        logoutFrom(owner, owner.post(batch.action(), invalidPrice), route, 400);
        assertEquals(before, prices(f, event));
        System.out.println("HTTP page-two batch/individual pricing retains paging, selection and invalid input; corrected submits are isolated; malformed paging/CSRF and validation logout: PASS");
    }

    private java.util.Map<Long, BigDecimal> prices(P3Fixture fixture, long event) {
        return InventorySetupIT.rows(fixture, event).items().stream().collect(java.util.stream.Collectors.toMap(seat -> seat.id(), seat -> seat.price()));
    }

    private record PricingForm(String action, java.util.Map<String, String> fields) { }

    private PricingForm pricingForm(String body, Long inventory) {
        var forms = java.util.regex.Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>").matcher(body);
        while (forms.find()) {
            if (!"post".equals(attribute(forms.group(1), "method"))) { continue; }
            var fields = new LinkedHashMap<String, String>();
            var inputs = java.util.regex.Pattern.compile("<input\\b[^>]*>").matcher(forms.group(2));
            while (inputs.find()) {
                if ("hidden".equals(attribute(inputs.group(), "type"))) { fields.put(attribute(inputs.group(), "name"), attribute(inputs.group(), "value")); }
            }
            if (!"price".equals(fields.get("action"))) { continue; }
            if (inventory == null ? fields.containsKey("eventSeatId") : !String.valueOf(inventory).equals(fields.get("eventSeatId"))) { continue; }
            String action = attribute(forms.group(1), "action").replace("&amp;", "&");
            assertNull(java.net.URI.create(action).getRawQuery(), "Inventory POST actions must remain query-free.");
            return new PricingForm(action, fields);
        }
        throw new AssertionError("Expected rendered pricing form was not found; body suppressed.");
    }

    private String attribute(String tag, String name) {
        var value = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return value.find() ? value.group(1) : "";
    }

    private void publicAvailability(P4Fixture fixture) throws Exception {
        P3Fixture f = fixture.f;
        long actor = f.user("ORGANIZER"), customer = f.user("CUSTOMER"), category = f.category(), venue = f.venue();
        for (int i = 0; i < 4; i++) { f.seat(venue); }
        long event = f.event(actor, category, venue, f.prefix + "Availability", "DRAFT", 30);
        var service = InventorySetupIT.service(f); service.generate(actor, event, "100.25"); service.publish(actor, event);
        var rows = InventorySetupIT.rows(f, event).items();
        var now = f.access.read(c -> new com.eventbooking.dao.EventDao().now(c).withNano(0));
        fixture.history(customer, event, rows.get(0).id(), "HELD", now.plusMinutes(2));
        fixture.history(customer, event, rows.get(1).id(), "HELD", now.minusSeconds(1));
        fixture.history(customer, event, rows.get(2).id(), "SOLD", now);
        HttpBrowser guest = new HttpBrowser(); String route = "events/seats?eventId=" + event;
        var page = guest.get(route); status(200, page);
        assertTrue(page.body().contains(">HELD</span>")); assertTrue(page.body().contains(">UNAVAILABLE</span>"));
        assertTrue(page.body().contains(">AVAILABLE</span>")); assertTrue(page.body().contains("100.25 - 100.25 VND"));
        for (String internal : new String[]{"current_booking_id", "hold_expires_at", "booking_code", f.email(customer), "name=\"action\"", "name=\"eventSeatId\""}) { assertFalse(page.body().contains(internal)); }
        status(200, guest.get(route + "&size=1")); status(200, guest.get(route + "&page=999999"));
        status(400, guest.get(route + "&page=0")); status(400, guest.get(route + "&size=51"));
        status(405, guest.post("events/seats", fields("eventId", "" + event, "action", "generate", "price", "0")));
        assertEquals(2L, f.access.read(c -> com.eventbooking.dao.JdbcAccess.scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='HELD'", event)).longValue());
        long empty = f.event(actor, category, venue, f.prefix + "Empty", "PUBLISHED", 30);
        var emptyPage = guest.get("events/seats?eventId=" + empty); status(200, emptyPage); assertTrue(emptyPage.body().contains("No inventory has been generated"));
        long privateEvent = f.event(actor, category, venue, f.prefix + "Private", "DRAFT", 30);
        status(404, guest.get("events/seats?eventId=" + privateEvent));
        for (String path : new String[]{"WEB-INF/views/events/seats.jsp", "WEB-INF/views/organizer/inventory.jsp"}) { status(404, guest.get(path)); }
        System.out.println("HTTP read-only public availability, held/expired/sold states, price summaries, safe projections, hidden IDs, empty/paged inventory and non-mutation: PASS");
    }

    private void rejectTokens(HttpBrowser browser, Form form, java.util.Map<String, String> values) throws Exception {
        for (String invalid : new String[]{null, "x".repeat(43), token(new HttpBrowser().get("register").body()), browser.staleToken()}) {
            var changed = new LinkedHashMap<>(values); if (invalid == null) { changed.remove("_csrf"); } else { changed.put("_csrf", invalid); }
            status(403, browser.post(form.action(), changed));
        }
    }

    private void logoutFrom(HttpBrowser browser, java.net.http.HttpResponse<String> response, String protectedRoute, int expected) throws Exception {
        status(expected, response);
        var logout = java.util.regex.Pattern.compile("(?s)<form\\b[^>]*action=\"([^\"]*/logout)\"[^>]*>(.*?)</form>").matcher(response.body());
        assertTrue(logout.find(), "Missing rendered logout form; body suppressed.");
        browser.redirect(browser.post(logout.group(1), fields("_csrf", token(logout.group(2)))), "");
        browser.redirect(browser.get(protectedRoute), "login");
    }
}
