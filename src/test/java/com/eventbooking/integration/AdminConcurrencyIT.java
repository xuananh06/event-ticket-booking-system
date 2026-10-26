package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Role;
import com.eventbooking.security.PasswordHasher;
import com.eventbooking.service.AdminUserService;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminConcurrencyIT {
    @Test
    void twoIndependentConnectionsCannotDisableEveryActiveAdmin() throws Exception {
        try (TestDatabase db = new TestDatabase()) {
            String schema = "p3_admin_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection setup = db.source.getConnection()) {
                try (Statement s = setup.createStatement()) {
                    s.setQueryTimeout(10);
                    s.execute("CREATE SCHEMA " + schema);
                    s.execute("SELECT TOP(0) * INTO " + schema + ".users FROM dbo.users");
                    s.execute("SELECT TOP(0) * INTO " + schema + ".events FROM dbo.events");
                    s.execute("SELECT TOP(0) * INTO " + schema + ".bookings FROM dbo.bookings");
                }
                long first = scalar(setup, "INSERT " + schema + ".users(email,password_hash,full_name,role,is_active,created_at) OUTPUT INSERTED.id SELECT N'first@example.test',password_hash,N'First Admin','ADMIN',1,SYSUTCDATETIME() FROM dbo.users WHERE email=N'admin@example.test'");
                long second = scalar(setup, "INSERT " + schema + ".users(email,password_hash,full_name,role,is_active,created_at) OUTPUT INSERTED.id SELECT N'second@example.test',password_hash,N'Second Admin','ADMIN',1,SYSUTCDATETIME() FROM dbo.users WHERE email=N'admin@example.test'");
                // Only the schema qualifier is redirected; all production transactions run on real SQL Server.
                DataSource isolated = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object value = invoke(db.source, method, args);
                    if (value instanceof Connection connection) {
                        return connection(connection, schema);
                    }
                    return value;
                });
                AdminUserService service = new AdminUserService(new JdbcAccess(Optional.of(isolated)), new AdminUserDao(), new PasswordHasher());
                ExecutorService executor = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
                try {
                    Future<Boolean> a = executor.submit(() -> attempt(service, first, second, ready, start));
                    Future<Boolean> b = executor.submit(() -> attempt(service, second, first, ready, start));
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    start.countDown();
                    assertNotEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
                    assertEquals(1, scalar(setup, "SELECT COUNT_BIG(*) FROM " + schema + ".users WHERE role='ADMIN' AND is_active=1"));
                    assertEquals(1, scalar(setup, "SELECT COUNT_BIG(*) FROM dbo.users WHERE email=N'admin@example.test' AND role='ADMIN' AND is_active=1"));
                } finally {
                    start.countDown();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                }
            } finally {
                // Exact test-owned schema only. Seeded users and application tables are never changed.
                assertTrue(schema.matches("p3_admin_[a-f0-9]{32}"));
                try (Connection cleanup = db.source.getConnection(); Statement s = cleanup.createStatement()) {
                    s.setQueryTimeout(10);
                    s.execute("DROP TABLE IF EXISTS " + schema + ".bookings");
                    s.execute("DROP TABLE IF EXISTS " + schema + ".events");
                    s.execute("DROP TABLE IF EXISTS " + schema + ".users");
                    s.execute("DROP SCHEMA " + schema);
                }
            }
        }
    }

    private boolean attempt(AdminUserService service, long actor, long target, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            service.status(actor, target, false);
            return true;
        } catch (DomainException rejected) {
            assertTrue(rejected.getStatus() == 403 || rejected.getStatus() == 409);
            return false;
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Connection connection(Connection target, String schema) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                args = args.clone();
                args[0] = sql.replace("dbo.users", schema + ".users").replace("dbo.events", schema + ".events").replace("dbo.bookings", schema + ".bookings");
            }
            return invoke(target, method, args);
        });
    }
}
