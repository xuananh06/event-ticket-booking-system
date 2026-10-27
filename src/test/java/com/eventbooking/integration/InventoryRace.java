package com.eventbooking.integration;

import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.exception.DomainException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opens two real independent connections before releasing either use case. */
final class InventoryRace {
    @FunctionalInterface interface Action { Object run(JdbcAccess database) throws Exception; }
    record Outcome(Object value, int status) { }

    static List<Outcome> run(P3Fixture fixture, Action first, Action second) throws Exception {
        CountDownLatch connected = new CountDownLatch(2), start = new CountDownLatch(1);
        Set<Connection> connections = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            Object value;
            try { value = method.invoke(fixture.db.source, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (value instanceof Connection connection) {
                connections.add(connection); connected.countDown();
                try { assertTrue(start.await(10, TimeUnit.SECONDS)); }
                catch (Throwable failure) { connection.close(); throw failure; }
            }
            return value;
        });
        JdbcAccess database = new JdbcAccess(Optional.of(source));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> attempt(first, database));
            var b = executor.submit(() -> attempt(second, database));
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            assertEquals(2, connections.size());
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Outcome attempt(Action action, JdbcAccess database) throws Exception {
        try { return new Outcome(action.run(database), 200); }
        catch (DomainException rejected) { return new Outcome(null, rejected.getStatus()); }
    }
}
