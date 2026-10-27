package com.eventbooking.integration;

import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.service.CatalogService;
import com.eventbooking.service.EventService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationIT {
    @Test void exactCoveragePublishesOnceAndFreezesSetup() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            var service = InventorySetupIT.service(f);
            long actor = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            f.seat(venue); service.generate(actor, event, "0");
            assertTrue(service.view(actor, event, 1, 10).ready());
            f.seat(venue);
            assertFalse(service.view(actor, event, 1, 10).ready());
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            assertEquals(1, InventorySetupIT.rows(f, event).total());
            service.generate(actor, event, "25.50");
            service.publish(actor, event);
            var published = new CatalogService(f.access, new EventDao()).detail(event);
            assertEquals("PUBLISHED", published.status());
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> service.generate(actor, event, "500")).getStatus());
            long id = InventorySetupIT.rows(f, event).items().get(0).id();
            assertEquals(409, assertThrows(DomainException.class, () -> service.price(actor, event, new String[]{"" + id}, "500")).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> new EventService(f.access, new EventDao()).save(actor, event, category, venue, "Changed", "Description", published.getStartsInput(), published.getEndsInput())).getStatus());
            f.seat(venue);
            assertEquals(2, InventorySetupIT.rows(f, event).total());
            assertEquals(new BigDecimal("0.00"), InventorySetupIT.rows(f, event).items().get(0).price());
        }
    }

    @Test void invalidParentsPhysicalMembersStoredDetailsAndStatusCannotPublish() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            var service = InventorySetupIT.service(f);
            long actor = f.user("ORGANIZER"), other = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            long seat = f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30); service.generate(actor, event, "1");
            assertEquals(403, assertThrows(DomainException.class, () -> service.publish(f.admin, event)).getStatus());
            assertEquals(404, assertThrows(DomainException.class, () -> service.publish(other, event)).getStatus());
            f.references.categoryStatus(f.admin, category, false);
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            f.references.categoryStatus(f.admin, category, true); f.references.venueStatus(f.admin, venue, false);
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            f.references.venueStatus(f.admin, venue, true);
            f.access.write(c -> { update(c, "UPDATE dbo.seats SET is_active=0 WHERE id=?", seat); return null; });
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            f.access.write(c -> { update(c, "UPDATE dbo.seats SET is_active=1 WHERE id=?", seat); update(c, "UPDATE dbo.events SET description=N' ' WHERE id=?", event); return null; });
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            f.access.write(c -> { update(c, "UPDATE dbo.events SET description=N'Valid' WHERE id=?", event); return null; });
            f.db.changeAccount(f.email(actor), "ORGANIZER", false);
            assertEquals(403, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            f.db.changeAccount(f.email(actor), "ORGANIZER", true);
            for (String status : new String[]{"PUBLISHED", "CLOSED", "CANCELLED"}) {
                f.access.write(c -> { update(c, "UPDATE dbo.events SET status=? WHERE id=?", status, event); return null; });
                assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            }
        }
    }

    @Test void historyAndExpiredHeldInventoryCannotPublish() throws Exception {
        try (P4Fixture fixture = new P4Fixture()) {
            P3Fixture f = fixture.f;
            long actor = f.user("ORGANIZER"), customer = f.user("CUSTOMER"), category = f.category(), venue = f.venue();
            f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            var service = InventorySetupIT.service(f); service.generate(actor, event, "10");
            long id = InventorySetupIT.rows(f, event).items().get(0).id();
            fixture.history(customer, event, id, "HELD", f.access.read(c -> new EventDao().now(c).minusSeconds(1).withNano(0)));
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            // Even AVAILABLE current inventory cannot erase prior transaction history.
            f.access.write(c -> { update(c, "UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL,hold_expires_at=NULL WHERE id=? AND event_id=?", id, event); return null; });
            assertEquals(409, assertThrows(DomainException.class, () -> service.publish(actor, event)).getStatus());
            assertEquals("DRAFT", new EventService(f.access, new EventDao()).owned(actor, event).status());
        }
    }
}
