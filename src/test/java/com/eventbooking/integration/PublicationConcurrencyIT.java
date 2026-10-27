package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.service.EventService;
import com.eventbooking.service.InventoryService;
import com.eventbooking.service.ReferenceService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationConcurrencyIT {
    @Test void pricingAndPublicationHaveOnlySerializedOutcomes() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(); f.seat(venue);
            long event = f.event(actor, f.category(), venue, f.prefix, "DRAFT", 30);
            InventorySetupIT.service(f).generate(actor, event, "10");
            long id = InventorySetupIT.rows(f, event).items().get(0).id();
            var outcomes = InventoryRace.run(f,
                    access -> { service(access).price(actor, event, new String[]{"" + id}, "50"); return "priced"; },
                    access -> { service(access).publish(actor, event); return "published"; });
            assertEquals(200, outcomes.get(1).status());
            assertTrue(outcomes.get(0).status() == 200 || outcomes.get(0).status() == 409);
            assertEquals("PUBLISHED", new EventService(f.access, new EventDao()).owned(actor, event).status());
            assertEquals(new BigDecimal(outcomes.get(0).status() == 200 ? "50.00" : "10.00"), InventorySetupIT.rows(f, event).items().get(0).price());
        }
    }

    @Test void missingSeatGenerationAndPublicationHaveOnlySerializedOutcomes() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(); f.seat(venue);
            long event = f.event(actor, f.category(), venue, f.prefix, "DRAFT", 30);
            InventorySetupIT.service(f).generate(actor, event, "10"); f.seat(venue);
            var outcomes = InventoryRace.run(f,
                    access -> service(access).generate(actor, event, "20"),
                    access -> { service(access).publish(actor, event); return "published"; });
            assertEquals(200, outcomes.get(0).status()); assertEquals(1, outcomes.get(0).value());
            assertTrue(outcomes.get(1).status() == 200 || outcomes.get(1).status() == 409);
            assertEquals(outcomes.get(1).status() == 200 ? "PUBLISHED" : "DRAFT", new EventService(f.access, new EventDao()).owned(actor, event).status());
            var rows = InventorySetupIT.rows(f, event); assertEquals(2, rows.total());
            assertEquals(new BigDecimal("10.00"), rows.items().get(0).price()); assertEquals(new BigDecimal("20.00"), rows.items().get(1).price());
        }
    }

    @Test void adminSeatInsertionAndPublicationFreezeTheWinningLayout() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(); f.seat(venue);
            long event = f.event(actor, f.category(), venue, f.prefix, "DRAFT", 30); InventorySetupIT.service(f).generate(actor, event, "10");
            var outcomes = InventoryRace.run(f,
                    access -> new ReferenceService(access, new ReferenceDao()).saveSeat(f.admin, null, venue, "B", 1, true),
                    access -> { service(access).publish(actor, event); return "published"; });
            assertEquals(200, outcomes.get(0).status()); f.seats.add((Long) outcomes.get(0).value());
            assertTrue(outcomes.get(1).status() == 200 || outcomes.get(1).status() == 409);
            assertEquals(outcomes.get(1).status() == 200 ? "PUBLISHED" : "DRAFT", new EventService(f.access, new EventDao()).owned(actor, event).status());
            assertEquals(1, InventorySetupIT.rows(f, event).total());
            assertEquals(2, f.references.seats(f.admin, venue, true, 1, 10).total());
        }
    }

    @Test void categoryDeactivationCannotRacePublicationIntoAnInvalidState() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(), category = f.category(); f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30); InventorySetupIT.service(f).generate(actor, event, "10");
            var outcomes = InventoryRace.run(f,
                    access -> { new ReferenceService(access, new ReferenceDao()).categoryStatus(f.admin, category, false); return "disabled"; },
                    access -> { service(access).publish(actor, event); return "published"; });
            assertEquals(1, outcomes.stream().filter(outcome -> outcome.status() == 200).count());
            assertEquals(1, outcomes.stream().filter(outcome -> outcome.status() == 409).count());
            boolean published = outcomes.get(1).status() == 200;
            assertEquals(published, f.references.category(f.admin, category).active());
            assertEquals(published ? "PUBLISHED" : "DRAFT", new EventService(f.access, new EventDao()).owned(actor, event).status());
        }
    }

    @Test void draftProfileEditCannotCommitAfterPublication() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(), category = f.category(); f.seat(venue);
            long event = f.event(actor, category, venue, f.prefix, "DRAFT", 30); InventorySetupIT.service(f).generate(actor, event, "10");
            var original = new EventService(f.access, new EventDao()).owned(actor, event);
            var outcomes = InventoryRace.run(f,
                    access -> new EventService(access, new EventDao()).save(actor, event, category, venue, "Edited draft", "Description", original.getStartsInput(), original.getEndsInput()),
                    access -> { service(access).publish(actor, event); return "published"; });
            assertEquals(200, outcomes.get(1).status());
            assertTrue(outcomes.get(0).status() == 200 || outcomes.get(0).status() == 409);
            var result = new EventService(f.access, new EventDao()).owned(actor, event);
            assertEquals("PUBLISHED", result.status());
            assertEquals(outcomes.get(0).status() == 200 ? "Edited draft" : original.title(), result.title());
        }
    }

    @Test void waitingOnPhysicalLocksUntilStartRejectsPublicationAndKeepsDraft() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long actor = f.user("ORGANIZER"), venue = f.venue(); long seat = f.seat(venue);
            long event = f.event(actor, f.category(), venue, f.prefix, "DRAFT", 30); InventorySetupIT.service(f).generate(actor, event, "10");
            CountDownLatch lockingSeats = new CountDownLatch(1);
            DataSource observed = observed(f.db.source, lockingSeats);
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = f.db.source.getConnection()) {
                // Only this test-owned event gets a short database-time boundary.
                update(blocker, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,2,SYSUTCDATETIME()),ends_at=DATEADD(MINUTE,3,SYSUTCDATETIME()) WHERE id=?", event);
                blocker.setAutoCommit(false);
                update(blocker, "UPDATE dbo.seats SET seat_number=seat_number WHERE id=?", seat);
                var publication = executor.submit(() -> {
                    try { service(new JdbcAccess(Optional.of(observed))).publish(actor, event); return 200; }
                    catch (DomainException rejected) { return rejected.getStatus(); }
                });
                try {
                    assertTrue(lockingSeats.await(5, TimeUnit.SECONDS));
                    assertFalse(publication.isDone());
                    try (PreparedStatement wait = statement(blocker, "DECLARE @deadline datetime2(3)=(SELECT starts_at FROM dbo.events WHERE id=?); WHILE SYSUTCDATETIME()<=@deadline WAITFOR DELAY '00:00:00.050';", event)) { wait.execute(); }
                } finally { blocker.rollback(); }
                assertEquals(409, publication.get(10, TimeUnit.SECONDS));
                assertEquals("DRAFT", new EventService(f.access, new EventDao()).owned(actor, event).status());
                assertEquals(new BigDecimal("10.00"), InventorySetupIT.rows(f, event).items().get(0).price());
                // The final conditional UPDATE also rejects an already-started event independently.
                var past = new EventService(f.access, new EventDao()).owned(actor, event);
                assertEquals(409, assertThrows(DomainException.class, () -> f.access.write(c -> { new InventoryDao().publish(c, past); return null; })).getStatus());
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private static InventoryService service(JdbcAccess access) { return new InventoryService(access, new EventDao(), new InventoryDao()); }

    private static DataSource observed(DataSource source, CountDownLatch lockingSeats) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            Object value = invoke(source, method, args);
            if (value instanceof Connection connection) {
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    Object result = invoke(connection, m, a);
                    if (result instanceof PreparedStatement statement && a != null && a[0] instanceof String sql && sql.startsWith("SELECT COUNT_BIG(*) FROM dbo.seats WITH")) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                            if (operation.getName().equals("executeQuery")) { lockingSeats.countDown(); }
                            return invoke(statement, operation, parameters);
                        });
                    }
                    return result;
                });
            }
            return value;
        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
