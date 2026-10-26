package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;
import com.eventbooking.service.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogIT {
    private EventFilter filter(String q, String category, String venue, String from, String to, String page, String size) {
        return EventFilter.parse(q, category, venue, from, to, "", page, size);
    }

    @Test
    void visibilityUsesPublishedFutureAndActiveReferencesForEveryRead() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            CatalogService catalog = new CatalogService(f.access, new EventDao());
            long organizer = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            long visible = f.event(organizer, category, venue, f.prefix + "Visible", "PUBLISHED", 30);
            for (String status : new String[]{"DRAFT", "CANCELLED", "CLOSED"}) {
                long hidden = f.event(organizer, category, venue, f.prefix + status, status, 30);
                assertEquals(404, assertThrows(DomainException.class, () -> catalog.detail(hidden)).getStatus());
            }
            long past = f.event(organizer, category, venue, f.prefix + "Past", "PUBLISHED", -3);
            assertEquals(404, assertThrows(DomainException.class, () -> catalog.detail(past)).getStatus());
            assertEquals(1, catalog.list(filter(f.prefix, "", "", "", "", "1", "10")).total());
            assertEquals(visible, catalog.detail(visible).id());
            // Changes only the explicitly owned fixture parents, never seeded records.
            f.access.write(c -> {
                update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", category);
                return null;
            });
            assertEquals(0, catalog.list(filter(f.prefix, "", "", "", "", "1", "10")).total());
            assertEquals(404, assertThrows(DomainException.class, () -> catalog.detail(visible)).getStatus());
            f.access.write(c -> {
                update(c, "UPDATE dbo.categories SET is_active=1 WHERE id=?", category);
                update(c, "UPDATE dbo.venues SET is_active=0 WHERE id=?", venue);
                return null;
            });
            assertEquals(404, assertThrows(DomainException.class, () -> catalog.detail(visible)).getStatus());
            f.access.write(c -> {
                update(c, "UPDATE dbo.venues SET is_active=1 WHERE id=?", venue);
                update(c, "UPDATE dbo.users SET is_active=0 WHERE id=?", organizer);
                return null;
            });
            assertEquals(404, assertThrows(DomainException.class, () -> catalog.detail(visible)).getStatus());
            assertEquals(0, catalog.list(filter(f.prefix, "", "", "", "", "1", "10")).total());
            assertEquals(404, assertThrows(DomainException.class, () -> catalog.detail(Long.MAX_VALUE)).getStatus());
        }
    }

    @Test
    void literalCombinedFilteringDateBoundariesAndStablePaging() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            CatalogService catalog = new CatalogService(f.access, new EventDao());
            long actor = f.user("ORGANIZER"), category = f.category(), venue = f.venue(), otherVenue = f.venue();
            String title = f.prefix + "100%_[x]~";
            long first = f.event(actor, category, venue, title, "PUBLISHED", 30), second = f.event(actor, category, venue, title + " two", "PUBLISHED", 30);
            f.event(actor, category, otherVenue, title + " elsewhere", "PUBLISHED", 30);
            var sameStart = f.access.read(c -> new EventDao().now(c).plusDays(30).withNano(0));
            f.access.write(c -> {
                update(c, "UPDATE dbo.events SET starts_at=?,ends_at=? WHERE id=?", sameStart, sameStart.plusHours(2), first);
                update(c, "UPDATE dbo.events SET starts_at=?,ends_at=? WHERE id=?", sameStart, sameStart.plusHours(2), second);
                return null;
            });
            String date = EventTimes.input(sameStart).substring(0, 10);
            var query = filter(title, String.valueOf(category), String.valueOf(venue), date, date, "1", "1");
            Page<Event> page = catalog.list(query);
            assertEquals(2, page.total());
            assertEquals(first, page.items().get(0).id());
            assertEquals(second, catalog.list(filter(title, String.valueOf(category), String.valueOf(venue), date, date, "2", "1")).items().get(0).id());
            assertTrue(catalog.list(filter(title, String.valueOf(category), String.valueOf(venue), date, date, "3", "1")).items().isEmpty());
            assertEquals(0, catalog.list(filter(f.prefix + "100X", "", "", "", "", "1", "10")).total());
            assertTrue(catalog.list(filter("' OR 1=1 --", "", "", "", "", "1", "10")).items().isEmpty());
        }
    }
}
