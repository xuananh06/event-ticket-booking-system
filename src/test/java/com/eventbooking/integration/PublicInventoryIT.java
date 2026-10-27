package com.eventbooking.integration;

import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.service.PublicInventoryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicInventoryIT {
    @Test void databaseTimeExpiryBoundariesAgreeBetweenLayoutAndSummaryWithoutWrites() throws Exception {
        try (P4Fixture fixture = new P4Fixture()) {
            P3Fixture f = fixture.f;
            long actor = f.user("ORGANIZER"), customer = f.user("CUSTOMER"), venue = f.venue();
            for (int i = 0; i < 5; i++) { f.seat(venue); }
            long event = f.event(actor, f.category(), venue, f.prefix, "DRAFT", 30);
            var service = InventorySetupIT.service(f); service.generate(actor, event, "50");
            var rows = InventorySetupIT.rows(f, event).items();
            service.price(actor, event, new String[]{"" + rows.get(0).id()}, "10");
            service.publish(actor, event);
            var reference = f.access.read(c -> new EventDao().now(c).withNano(0).plusMinutes(1));
            fixture.history(customer, event, rows.get(0).id(), "HELD", reference);
            fixture.history(customer, event, rows.get(1).id(), "HELD", reference.plusMinutes(1));
            fixture.history(customer, event, rows.get(2).id(), "SOLD", reference);
            f.access.write(c -> { update(c, "UPDATE dbo.seats SET is_active=0 WHERE id=?", rows.get(3).seatId()); return null; });
            for (int offset : new int[]{-1, 0, 1}) {
                var now = reference.plusNanos(offset * 1000000L);
                var page = f.access.read(c -> new InventoryDao().page(c, event, now, 1, 50, true));
                var summary = f.access.read(c -> new InventoryDao().summary(c, event, now, true));
                assertEquals(5, summary.total());
                assertEquals(offset < 0 ? 1 : 2, summary.available());
                assertEquals(offset < 0 ? 2 : 1, summary.held());
                assertEquals(2, summary.unavailable());
                assertEquals(summary.available(), page.items().stream().filter(seat -> seat.state().equals("AVAILABLE")).count());
                assertEquals(summary.held(), page.items().stream().filter(seat -> seat.state().equals("HELD")).count());
                assertEquals(new BigDecimal(offset < 0 ? "50.00" : "10.00"), summary.minimumPrice());
                assertEquals(new BigDecimal("50.00"), summary.maximumPrice());
            }
            assertEquals(2L, f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='HELD' AND current_booking_id IS NOT NULL AND hold_expires_at IS NOT NULL", event)).longValue());
            assertEquals(1L, f.access.read(c -> scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=? AND state='SOLD'", event)).longValue());
        }
    }

    @Test void everyPublicQueryUsesTheExistingEligibilityPredicate() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(), category = f.category(); f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            var inventory = InventorySetupIT.service(f); inventory.generate(actor, event, "10");
            var publicInventory = service(f);
            assertHidden(f, publicInventory, event);
            inventory.publish(actor, event); assertEquals(1, publicInventory.view(event, 1, 10).page().total());
            f.access.write(c -> { update(c, "UPDATE dbo.categories SET is_active=0 WHERE id=?", category); return null; });
            assertHidden(f, publicInventory, event);
            f.access.write(c -> { update(c, "UPDATE dbo.categories SET is_active=1 WHERE id=?", category); update(c, "UPDATE dbo.venues SET is_active=0 WHERE id=?", venue); return null; });
            assertHidden(f, publicInventory, event);
            f.access.write(c -> { update(c, "UPDATE dbo.venues SET is_active=1 WHERE id=?", venue); return null; });
            f.db.changeAccount(f.email(actor), "ORGANIZER", false); assertHidden(f, publicInventory, event);
            f.db.changeAccount(f.email(actor), "CUSTOMER", true); assertHidden(f, publicInventory, event);
            f.db.changeAccount(f.email(actor), "ORGANIZER", true);
            for (String status : new String[]{"DRAFT", "CANCELLED", "CLOSED"}) {
                f.access.write(c -> { update(c, "UPDATE dbo.events SET status=? WHERE id=?", status, event); return null; });
                assertHidden(f, publicInventory, event);
            }
            f.access.write(c -> { update(c, "UPDATE dbo.events SET status='PUBLISHED',starts_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE id=?", event); return null; });
            assertHidden(f, publicInventory, event);
            assertEquals(404, assertThrows(DomainException.class, () -> publicInventory.view(Long.MAX_VALUE, 1, 10)).getStatus());
        }
    }

    @Test void pagingIsBoundedCompleteAndZeroInventoryRemainsReadable() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(), category = f.category();
            long empty = f.event(actor, category, venue, f.prefix + "Empty", "PUBLISHED", 30);
            var publicInventory = service(f);
            assertEquals(0, publicInventory.view(empty, 1, 50).summary().total());
            assertNull(publicInventory.view(empty, 1, 50).summary().minimumPrice());
            List<Long> ids = f.access.write(c -> {
                List<Long> seats = new ArrayList<>();
                for (int i = 1; i <= 53; i++) { seats.add(scalar(c, "INSERT dbo.seats(venue_id,row_label,seat_number) OUTPUT INSERTED.id VALUES (?,N'A',?)", venue, i)); }
                return seats;
            });
            f.seats.addAll(ids);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            InventorySetupIT.service(f).generate(actor, event, "0"); InventorySetupIT.service(f).publish(actor, event);
            var first = publicInventory.view(event, 1, 50);
            var second = publicInventory.view(event, 2, 50);
            assertEquals(53, first.page().total()); assertEquals(53, first.summary().available());
            assertEquals(50, first.page().items().size()); assertEquals(3, second.page().items().size());
            assertEquals(50, first.page().items().get(49).seatNumber()); assertEquals(51, second.page().items().get(0).seatNumber());
            assertTrue(publicInventory.view(event, 1000000, 50).page().items().isEmpty());
            assertEquals(new BigDecimal("0.00"), first.summary().minimumPrice());
            // Later physical additions never backfill an already published event.
            assertEquals(0, publicInventory.view(empty, 1, 50).page().total());
        }
    }

    static PublicInventoryService service(P3Fixture f) { return new PublicInventoryService(f.access, new EventDao(), new InventoryDao()); }

    private void assertHidden(P3Fixture f, PublicInventoryService service, long event) {
        assertEquals(404, assertThrows(DomainException.class, () -> service.view(event, 1, 10)).getStatus());
        var now = f.access.read(c -> new EventDao().now(c));
        assertEquals(0, f.access.read(c -> new InventoryDao().page(c, event, now, 1, 10, true)).total());
        assertEquals(0, f.access.read(c -> new InventoryDao().summary(c, event, now, true)).total());
    }
}
