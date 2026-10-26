package com.eventbooking.integration;

import com.eventbooking.dao.JdbcAccess;

import java.util.*;

import org.junit.jupiter.api.Test;

import static com.eventbooking.integration.HttpBrowser.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagementHttpCheck {
    @Test
    void deployedManagementAndBrowsing() throws Exception {
        HttpBrowser guest = new HttpBrowser();
        for (String route : new String[]{"admin/categories", "admin/venues", "admin/seats", "admin/users"}) {
            guest.redirect(guest.get(route), "login");
        }
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            status(503, guest.get("events"));
            status(503, guest.get("events/detail?id=1"));
            status(200, guest.get(""));
            status(200, guest.get("health"));
            System.out.println("HTTP unavailable catalog returns generic 503 while foundation pages stay available: PASS");
            return;
        }
        try (P3Fixture f = new P3Fixture()) {
            referenceScreens(f);
            venueSeatScreens(f);
            accountScreens(f);
            organizerScreens(f);
            publicScreens(f);
        }
    }

    private void referenceScreens(P3Fixture f) throws Exception {
        HttpBrowser browser = new HttpBrowser();
        browser.login(f.email(f.admin), P3Fixture.PASSWORD, "admin/home");
        for (String route : new String[]{"admin/categories", "admin/venues", "admin/seats"}) {
            status(200, browser.get(route));
        }
        String name = f.prefix + "HTTP <b>Category</b>";
        Form create = form(browser.get("admin/categories"), "save", null);
        Map<String, String> values = create.fields("action", "save", "id", "", "name", name, "active", "true");
        Map<String, String> invalid = new LinkedHashMap<>(values);
        invalid.remove("_csrf");
        status(403, browser.post(create.action(), invalid));
        invalid.put("_csrf", "x".repeat(43));
        status(403, browser.post(create.action(), invalid));
        invalid.put("_csrf", token(new HttpBrowser().get("register").body()));
        status(403, browser.post(create.action(), invalid));
        browser.redirect(browser.post(create.action(), values), "admin/categories");
        long id = f.access.read(c -> JdbcAccess.scalar(c, "SELECT id FROM dbo.categories WHERE name=?", name));
        f.categories.add(id);
        var page = browser.get("admin/categories?id=" + id);
        status(200, page);
        assertTrue(page.body().contains("&lt;b&gt;Category&lt;/b&gt;"));
        Form edit = form(page, "save", id);
        browser.redirect(browser.post(edit.action(), edit.fields("action", "save", "id", String.valueOf(id), "name", name, "active", "false")), "admin/categories");
        assertFalse(f.references.category(f.admin, id).active());
        status(200, browser.get("admin/categories?action=delete&id=" + id));
        assertFalse(f.references.category(f.admin, id).active());
        Form remove = form(browser.get("admin/categories"), "delete", id);
        browser.redirect(browser.post(remove.action(), remove.fields("action", "delete", "id", String.valueOf(id))), "admin/categories");
        long customer = f.user("CUSTOMER"), organizer = f.user("ORGANIZER");
        for (long account : new long[]{customer, organizer}) {
            HttpBrowser denied = new HttpBrowser();
            denied.login(f.email(account), P3Fixture.PASSWORD, account == customer ? "customer/home" : "organizer/home");
            for (String route : new String[]{"admin/categories", "admin/venues", "admin/seats", "admin/users"}) {
                status(403, denied.get(route));
            }
        }
        status(400, browser.get("admin/categories?id=9223372036854775808"));
        System.out.println("HTTP reference forms, persistence, escaping, mutation CSRF and role boundaries: PASS");
    }

    private void accountScreens(P3Fixture f) throws Exception {
        HttpBrowser browser = new HttpBrowser();
        browser.login(f.email(f.admin), P3Fixture.PASSWORD, "admin/home");
        String email = f.db.email();
        Form create = form(browser.get("admin/users"), "save", null);
        rejectTokens(browser, create, create.fields("action", "save", "id", "", "fullName", "Blocked Member", "email", email, "role", "CUSTOMER", "active", "true", "password", P3Fixture.PASSWORD, "confirmation", P3Fixture.PASSWORD));
        var invalid = browser.post(create.action(), create.fields("action", "save", "id", "", "fullName", "Created Member", "email", email, "role", "CUSTOMER", "active", "true", "password", "short", "confirmation", "different"));
        status(400, invalid);
        assertFalse(invalid.body().contains("value=\"short\""));
        assertTrue(invalid.body().contains("The password confirmation does not match."));
        browser.redirect(browser.post(create.action(), create.fields("action", "save", "id", "", "fullName", "HTTP Account <b>Member</b>", "email", email, "role", "ORGANIZER", "active", "true", "password", P3Fixture.PASSWORD, "confirmation", P3Fixture.PASSWORD)), "admin/users");
        long id = f.access.read(c -> JdbcAccess.scalar(c, "SELECT id FROM dbo.users WHERE email=?", email));
        var page = browser.get("admin/users?id=" + id);
        status(200, page);
        assertTrue(page.body().contains("&lt;b&gt;Member&lt;/b&gt;"));
        Form edit = form(page, "save", id);
        browser.redirect(browser.post(edit.action(), edit.fields("action", "save", "id", String.valueOf(id), "fullName", "Edited HTTP Member", "email", email, "role", "CUSTOMER", "active", "true", "password_hash", "forged", "password", "ignored")), "admin/users");
        assertEquals(com.eventbooking.model.Role.CUSTOMER, f.db.auth.authenticate(email, P3Fixture.PASSWORD).getRole());
        HttpBrowser member = new HttpBrowser();
        member.login(email, P3Fixture.PASSWORD, "customer/home");
        Form statusForm = form(browser.get("admin/users"), "status", id);
        browser.redirect(browser.post(statusForm.action(), statusForm.fields("action", "status", "id", String.valueOf(id), "active", "false")), "admin/users");
        member.redirect(member.get("customer/home"), "login");
        var own = browser.get("admin/users?id=" + f.admin);
        Form ownForm = form(own, "save", f.admin);
        status(409, browser.post(ownForm.action(), ownForm.fields("action", "save", "id", String.valueOf(f.admin), "fullName", "Fixture Member", "email", f.email(f.admin), "role", "CUSTOMER", "active", "true")));
        status(200, browser.get("admin/home"));
        assertFalse(browser.get("admin/users").body().contains("$2a$12$"));
        System.out.println("HTTP account creation/edit/status, credential retention, mass-assignment and self-admin guards: PASS");
    }

    private void venueSeatScreens(P3Fixture f) throws Exception {
        HttpBrowser browser = new HttpBrowser();
        browser.login(f.email(f.admin), P3Fixture.PASSWORD, "admin/home");
        Form venueForm = form(browser.get("admin/venues"), "save", null);
        String name = f.prefix + "HTTP Venue";
        var fields = venueForm.fields("action", "save", "id", "", "name", name, "address", "Campus <b>Address</b>", "active", "true");
        rejectTokens(browser, venueForm, fields);
        browser.redirect(browser.post(venueForm.action(), fields), "admin/venues");
        long venue = f.access.read(c -> JdbcAccess.scalar(c, "SELECT id FROM dbo.venues WHERE name=?", name));
        f.venues.add(venue);
        var venuePage = browser.get("admin/venues?id=" + venue);
        assertTrue(venuePage.body().contains("&lt;b&gt;Address&lt;/b&gt;"));
        Form seatForm = form(browser.get("admin/seats"), "save", null);
        var seatFields = seatForm.fields("action", "save", "id", "", "venueId", String.valueOf(venue), "rowLabel", "B", "seatNumber", "3", "active", "true");
        rejectTokens(browser, seatForm, seatFields);
        browser.redirect(browser.post(seatForm.action(), seatFields), "admin/seats");
        long seat = f.access.read(c -> JdbcAccess.scalar(c, "SELECT id FROM dbo.seats WHERE venue_id=? AND row_label='B' AND seat_number=3", venue));
        f.seats.add(seat);
        status(409, browser.post(seatForm.action(), seatFields));
        Form statusForm = form(browser.get("admin/seats?filterVenue=" + venue), "status", seat);
        browser.redirect(browser.post(statusForm.action(), statusForm.fields("action", "status", "id", String.valueOf(seat), "active", "false")), "admin/seats");
        assertFalse(f.references.seat(f.admin, seat).active());
        status(200, browser.get("admin/seats?action=delete&id=" + seat));
        assertNotNull(f.references.seat(f.admin, seat));
        Form remove = form(browser.get("admin/seats?filterVenue=" + venue), "delete", seat);
        browser.redirect(browser.post(remove.action(), remove.fields("action", "delete", "id", String.valueOf(seat))), "admin/seats");
        Form removeVenue = form(browser.get("admin/venues"), "delete", venue);
        browser.redirect(browser.post(removeVenue.action(), removeVenue.fields("action", "delete", "id", String.valueOf(venue))), "admin/venues");
        System.out.println("HTTP venue/seat rendered CRUD forms, duplicate conflicts, active filters and negative CSRF: PASS");
    }

    private void organizerScreens(P3Fixture f) throws Exception {
        long actor = f.user("ORGANIZER"), other = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
        HttpBrowser browser = new HttpBrowser();
        browser.login(f.email(actor), P3Fixture.PASSWORD, "organizer/home");
        HttpBrowser outsider = new HttpBrowser();
        outsider.login(f.email(other), P3Fixture.PASSWORD, "organizer/home");
        Form create = form(browser.get("organizer/events"), "save", null);
        var start = f.access.read(c -> new com.eventbooking.dao.EventDao().now(c).plusDays(40).withNano(0));
        String starts = com.eventbooking.service.EventTimes.input(start), ends = com.eventbooking.service.EventTimes.input(start.plusHours(2));
        String title = f.prefix + "HTTP Event";
        var values = create.fields("action", "save", "id", "", "title", title, "description", "<script>fixture</script>\nSecond line", "category", String.valueOf(category), "venue", String.valueOf(venue), "startsAt", starts, "endsAt", ends, "organizer_id", String.valueOf(other), "status", "PUBLISHED");
        rejectTokens(browser, create, values);
        var tooLong = new LinkedHashMap<>(values);
        tooLong.put("description", "x".repeat(10001));
        status(400, browser.post(create.action(), tooLong));
        browser.redirect(browser.post(create.action(), values), "organizer/events");
        long id = f.access.read(c -> JdbcAccess.scalar(c, "SELECT id FROM dbo.events WHERE title=?", title));
        f.events.add(id);
        var page = browser.get("organizer/events?id=" + id);
        status(200, page);
        assertTrue(page.body().contains("&lt;script&gt;fixture&lt;/script&gt;"));
        var service = new com.eventbooking.service.EventService(f.access, new com.eventbooking.dao.EventDao());
        assertEquals("DRAFT", service.owned(actor, id).status());
        assertEquals(actor, service.owned(actor, id).organizerId());
        status(404, outsider.get("organizer/events?id=" + id));
        Form otherForm = form(outsider.get("organizer/events"), "save", null);
        var forged = new LinkedHashMap<>(values);
        forged.put("id", String.valueOf(id));
        forged.put("_csrf", otherForm.token());
        status(404, outsider.post(otherForm.action(), forged));
        status(404, outsider.post(otherForm.action(), otherForm.fields("action", "delete", "id", String.valueOf(id))));
        f.references.categoryStatus(f.admin, category, false);
        page = browser.get("organizer/events?id=" + id);
        assertTrue(page.body().contains(f.prefix));
        Form edit = form(page, "save", id);
        values.put("id", String.valueOf(id));
        values.put("title", "Edited " + title);
        values.put("_csrf", edit.token());
        browser.redirect(browser.post(edit.action(), values), "organizer/events");
        status(200, browser.get("organizer/events?action=delete&id=" + id));
        assertNotNull(service.owned(actor, id));
        Form remove = form(browser.get("organizer/events"), "delete", id);
        browser.redirect(browser.post(remove.action(), remove.fields("action", "delete", "id", String.valueOf(id))), "organizer/events");
        f.events.remove(id);
        long published = f.event(actor, f.category(), venue, f.prefix + "Published", "PUBLISHED", 50);
        var readonly = browser.get("organizer/events?id=" + published);
        status(200, readonly);
        assertTrue(readonly.body().contains("This event is read-only."));
        status(409, browser.post(create.action(), create.fields("action", "delete", "id", String.valueOf(published))));
        System.out.println("HTTP organizer CRUD, independent ownership, draft-only writes, inactive references, timezone and escaping: PASS");
    }

    private void rejectTokens(HttpBrowser browser, Form form, Map<String, String> values) throws Exception {
        for (String invalid : new String[]{null, "x".repeat(43), token(new HttpBrowser().get("register").body()), browser.staleToken()}) {
            var changed = new LinkedHashMap<>(values);
            if (invalid == null) {
                changed.remove("_csrf");
            } else {
                changed.put("_csrf", invalid);
            }
            status(403, browser.post(form.action(), changed));
        }
    }

    private void publicScreens(P3Fixture f) throws Exception {
        long actor = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
        String title = f.prefix + "Public 100%_[x]~ <b>Event</b>";
        long first = f.event(actor, category, venue, title, "PUBLISHED", 60), second = f.event(actor, category, venue, title + " two", "PUBLISHED", 60);
        long draft = f.event(actor, category, venue, f.prefix + "Private", "DRAFT", 60);
        f.access.write(c -> {
            JdbcAccess.update(c, "UPDATE dbo.events SET description=? WHERE id=?", "Line one\n<script>fixture</script>", first);
            return null;
        });
        HttpBrowser guest = new HttpBrowser();
        String query = "events?q=" + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8) + "&category=" + category + "&venue=" + venue + "&size=1";
        var page = guest.get(query);
        status(200, page);
        assertTrue(page.body().contains("2 matching events"));
        assertTrue(page.body().contains("&lt;b&gt;Event&lt;/b&gt;"));
        var listed = new com.eventbooking.service.CatalogService(f.access, new com.eventbooking.dao.EventDao()).detail(first);
        assertTrue(page.body().contains("Starts: " + listed.getStartsLabel()));
        assertTrue(page.body().contains("Ends: " + listed.getEndsLabel()));
        var links = java.util.regex.Pattern.compile("href=\"([^\"]+)\"[^>]*>Next</a>").matcher(page.body());
        assertTrue(links.find());
        String next = links.group(1).replace("&amp;", "&");
        assertTrue(next.startsWith(guest.path("events") + "?"));
        assertTrue(next.contains("category=" + category));
        assertTrue(next.contains("venue=" + venue));
        status(200, guest.get(next));
        var detail = guest.get("events/detail?id=" + first);
        status(200, detail);
        assertTrue(detail.body().contains("&lt;script&gt;fixture&lt;/script&gt;"));
        assertTrue(detail.body().contains("Asia/Bangkok"));
        status(404, guest.get("events/detail?id=" + draft));
        status(404, guest.get("events/detail?id=9223372036854775807"));
        status(400, guest.get("events?page=0"));
        status(400, guest.get("events?size=51"));
        status(400, guest.get("events?from=invalid"));
        status(200, guest.get(query + "&page=999999"));
        for (String route : new String[]{"WEB-INF/views/events/list.jsp", "WEB-INF/views/events/detail.jsp", "WEB-INF/views/admin/users.jsp", "WEB-INF/views/organizer/events.jsp"}) {
            status(404, guest.get(route));
        }
        HttpBrowser signedIn = new HttpBrowser();
        signedIn.login(f.email(actor), P3Fixture.PASSWORD, "organizer/home");
        status(200, signedIn.get("events/detail?id=" + second));
        System.out.println("HTTP public visibility, combined literal filters, paging links, details/escaping, context paths and invalid input: PASS");
    }
}
