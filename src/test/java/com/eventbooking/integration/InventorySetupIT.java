package com.eventbooking.integration;

import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.service.InventoryService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class InventorySetupIT {
    @Test void simultaneousGenerationHasOneInsertionAndOneNoOp() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            f.seat(venue); f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            var outcomes = InventoryRace.run(f,
                    access -> new InventoryService(access, new EventDao(), new InventoryDao()).generate(actor, event, "10"),
                    access -> new InventoryService(access, new EventDao(), new InventoryDao()).generate(actor, event, "20"));
            assertTrue(outcomes.stream().allMatch(outcome -> outcome.status() == 200));
            assertEquals(java.util.Set.of(0, 2), outcomes.stream().map(InventoryRace.Outcome::value).collect(java.util.stream.Collectors.toSet()));
            var rows = rows(f, event);
            assertEquals(2, rows.total());
            assertEquals(1, rows.items().stream().map(row -> row.price()).distinct().count());
            assertTrue(rows.items().get(0).price().equals(new BigDecimal("10.00")) || rows.items().get(0).price().equals(new BigDecimal("20.00")));
        }
    }

    @Test void expiredHeldAndSoldHistoryCannotBeRecycledDuringSetup() throws Exception {
        try (P4Fixture fixture = new P4Fixture()) {
            P3Fixture f = fixture.f;
            long actor = f.user("ORGANIZER"), customer = f.user("CUSTOMER"), category = f.category(), venue = f.venue();
            f.seat(venue);
            for (String state : new String[]{"HELD", "SOLD"}) {
                long event = f.event(actor, category, venue, f.prefix + state, "DRAFT", 30);
                service(f).generate(actor, event, "10");
                long inventory = rows(f, event).items().get(0).id();
                var expiry = f.access.read(c -> new EventDao().now(c).minusSeconds(1).withNano(0));
                fixture.history(customer, event, inventory, state, expiry);
                assertEquals(409, assertThrows(DomainException.class, () -> service(f).generate(actor, event, "20")).getStatus());
                assertEquals(409, assertThrows(DomainException.class, () -> service(f).price(actor, event, new String[]{"" + inventory}, "20")).getStatus());
                assertEquals(new BigDecimal("10.00"), rows(f, event).items().get(0).price());
            }
        }
    }

    @Test void generationIsAdditiveRepeatableAndIndependentAcrossEvents() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            InventoryService service = service(f);
            long actor = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            long event = f.event(actor, category, venue, f.prefix + "Inventory", "DRAFT", 30);
            assertEquals(409, assertThrows(DomainException.class, () -> service.generate(actor, event, "10")).getStatus());
            long firstSeat = f.seat(venue), secondSeat = f.seat(venue), inactive = f.seat(venue);
            f.references.seatStatus(f.admin, inactive, false);
            assertEquals(2, service.generate(actor, event, "10.25"));
            var rows = rows(f, event);
            assertEquals(2, rows.total());
            long first = rows.items().get(0).id();
            service.price(actor, event, new String[]{String.valueOf(first)}, "0");
            assertEquals(0, service.generate(actor, event, "99"));
            assertEquals(first, rows(f, event).items().get(0).id());
            assertEquals(new BigDecimal("0.00"), rows(f, event).items().get(0).price());
            long thirdSeat = f.seat(venue);
            assertEquals(1, service.generate(actor, event, "55.50"));
            assertEquals(3, rows(f, event).total());
            assertEquals(new BigDecimal("10.25"), rows(f, event).items().get(1).price());
            assertEquals(new BigDecimal("55.50"), rows(f, event).items().get(2).price());
            long other = f.event(actor, category, venue, f.prefix + "Other", "DRAFT", 30);
            assertEquals(3, service.generate(actor, other, "20"));
            var ids = rows(f, event).items().stream().map(row -> String.valueOf(row.id())).toArray(String[]::new);
            service.price(actor, event, ids, "9999999999999999.99");
            assertTrue(rows(f, other).items().stream().allMatch(row -> row.price().equals(new BigDecimal("20.00"))));
            assertTrue(rows(f, event).items().stream().anyMatch(row -> row.seatId() == thirdSeat));
            assertTrue(rows(f, event).items().stream().anyMatch(row -> row.seatId() == firstSeat || row.seatId() == secondSeat));
            assertEquals(409, assertThrows(DomainException.class, () -> f.inventory(event, firstSeat, venue)).getStatus());
            long wrongVenue = f.venue(), wrongSeat = f.seat(wrongVenue);
            assertEquals(409, assertThrows(DomainException.class, () -> f.inventory(event, wrongSeat, venue)).getStatus());
        }
    }

    @Test void mixedSelectionAndInvalidInputsCannotPartiallyUpdate() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            InventoryService service = service(f);
            long actor = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            f.seat(venue); f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            long other = f.event(actor, category, venue, f.prefix + "Other", "DRAFT", 30);
            service.generate(actor, event, "10"); service.generate(actor, other, "20");
            long local = rows(f, event).items().get(0).id(), foreign = rows(f, other).items().get(0).id();
            assertEquals(404, assertThrows(DomainException.class, () -> service.price(actor, event, new String[]{"" + local, "" + foreign}, "500")).getStatus());
            assertTrue(rows(f, event).items().stream().allMatch(row -> row.price().equals(new BigDecimal("10.00"))));
            assertThrows(ValidationException.class, () -> service.price(actor, event, new String[]{"" + local}, "1.001"));
            assertThrows(ValidationException.class, () -> service.generate(actor, event, ""));
            assertEquals(2, rows(f, event).total());
        }
    }

    @Test void actorsOwnershipStatusInactiveParentsAndHistoryBlockSetup() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            InventoryService service = service(f);
            long actor = f.user("ORGANIZER"), other = f.user("ORGANIZER"), category = f.category(), venue = f.venue();
            f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30);
            assertEquals(403, assertThrows(DomainException.class, () -> service.generate(f.admin, event, "1")).getStatus());
            assertEquals(404, assertThrows(DomainException.class, () -> service.generate(other, event, "1")).getStatus());
            service.generate(actor, event, "1");
            long id = rows(f, event).items().get(0).id();
            f.references.categoryStatus(f.admin, category, false);
            assertEquals(409, assertThrows(DomainException.class, () -> service.price(actor, event, new String[]{"" + id}, "2")).getStatus());
            f.references.categoryStatus(f.admin, category, true);
            f.references.venueStatus(f.admin, venue, false);
            assertEquals(409, assertThrows(DomainException.class, () -> service.generate(actor, event, "2")).getStatus());
            f.references.venueStatus(f.admin, venue, true);
            f.access.write(c -> { update(c, "UPDATE dbo.events SET status='PUBLISHED' WHERE id=?", event); return null; });
            assertEquals(409, assertThrows(DomainException.class, () -> service.price(actor, event, new String[]{"" + id}, "2")).getStatus());
            f.access.write(c -> { update(c, "UPDATE dbo.events SET status='DRAFT' WHERE id=?", event); return null; });
            long customer = f.user("CUSTOMER");
            long booking = f.access.write(c -> scalar(c, "DECLARE @now datetime2(3)=SYSUTCDATETIME(); INSERT dbo.bookings(booking_code,customer_id,event_id,total_amount,created_at,expires_at) OUTPUT INSERTED.id VALUES (?,?,?,1,@now,DATEADD(MINUTE,5,@now))", "INV-" + java.util.UUID.randomUUID(), customer, event));
            f.bookings.add(booking);
            assertEquals(409, assertThrows(DomainException.class, () -> service.generate(actor, event, "2")).getStatus());
            assertEquals(new BigDecimal("1.00"), rows(f, event).items().get(0).price());
        }
    }

    static InventoryService service(P3Fixture f) { return new InventoryService(f.access, new EventDao(), new InventoryDao()); }

    static com.eventbooking.model.Page<com.eventbooking.model.InventorySeat> rows(P3Fixture f, long event) {
        return f.access.read(c -> new InventoryDao().page(c, event, new EventDao().now(c), 1, 50));
    }
}
