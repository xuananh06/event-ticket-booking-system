package com.eventbooking.integration;

import com.eventbooking.dao.StatisticsDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.model.Role;
import com.eventbooking.model.StatisticsSummary;
import com.eventbooking.service.PaymentTestWork;
import com.eventbooking.service.StatisticsService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Optional;
import java.util.concurrent.*;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsServiceIT {
    static StatisticsService service(P3Fixture f) { return new StatisticsService(Optional.of(f.db.source), new StatisticsDao()); }

    @Test void currentActiveRoleIsRevalidatedForEverySummary() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long customer = f.user("CUSTOMER"), organizer = f.user("ORGANIZER");
            var service = service(f);
            assertInstanceOf(StatisticsSummary.Customer.class, service.summary(customer, Role.CUSTOMER));
            assertInstanceOf(StatisticsSummary.Organizer.class, service.summary(organizer, Role.ORGANIZER));
            assertInstanceOf(StatisticsSummary.Admin.class, service.summary(f.admin, Role.ADMIN));
            assertEquals(403, assertThrows(DomainException.class, () -> service.summary(customer, Role.ADMIN)).getStatus());
            assertEquals(403, assertThrows(DomainException.class, () -> service.summary(Long.MAX_VALUE, Role.CUSTOMER)).getStatus());
            f.db.changeAccount(f.email(customer), "CUSTOMER", false);
            assertEquals(403, assertThrows(DomainException.class, () -> service.summary(customer, Role.CUSTOMER)).getStatus());
            f.db.changeAccount(f.email(organizer), "CUSTOMER", true);
            assertEquals(403, assertThrows(DomainException.class, () -> service.summary(organizer, Role.ORGANIZER)).getStatus());
            assertEquals(new StatisticsSummary.Customer(0, 0, 0, 0, 0), service.summary(organizer, Role.CUSTOMER));
        }
    }

    @Test void expiredReadProjectionDoesNotNormalizeOrReleaseOwnedHistory() throws Exception {
        try (P6Fixture x = new P6Fixture(1)) {
            long booking = x.hold(0);
            x.deadline(booking, -1);
            var before = x.snapshot();
            var service = service(x.f);
            assertEquals(new StatisticsSummary.Customer(1, 0, 0, 1, 0), service.summary(x.customer, Role.CUSTOMER));
            assertInstanceOf(StatisticsSummary.Organizer.class, service.summary(x.base.organizer, Role.ORGANIZER));
            assertInstanceOf(StatisticsSummary.Admin.class, service.summary(x.f.admin, Role.ADMIN));
            assertEquals(before, x.snapshot());
        }
    }

    @Test void realLockTimeoutIsSafeAndTheReadConnectionIsReleasedBeforeRetry() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            long customer = f.user("CUSTOMER");
            try (Connection blocker = f.db.source.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    update(blocker, "UPDATE dbo.users SET full_name=full_name WHERE id=?", customer);
                    var error = assertThrows(ServiceUnavailableException.class, () -> service(f).summary(customer, Role.CUSTOMER));
                    assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
                } finally { blocker.rollback(); }
            }
            assertEquals(new StatisticsSummary.Customer(0, 0, 0, 0, 0), service(f).summary(customer, Role.CUSTOMER));
        }
    }

    @Test void paymentCannotSplitTicketsAndMoneyAcrossIndependentSummaryStatements() throws Exception { transition(false); }

    @Test void refundCannotSplitTicketsAndMoneyAcrossIndependentSummaryStatements() throws Exception { transition(true); }

    private void transition(boolean refund) throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long booking = x.hold(0, 1);
            if (refund) { x.service().pay(x.customer, booking, P6Fixture.key(), "success"); }
            var before = x.snapshot();
            ReadGate gate = new ReadGate(x.f.db.source);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var reader = executor.submit(() -> new StatisticsService(Optional.of(gate.source()), new StatisticsDao())
                        .summary(x.base.organizer, Role.ORGANIZER));
                assertTrue(gate.afterTickets.await(10, TimeUnit.SECONDS), "Summary must reach the gap between ticket and money reads.");
                try (Connection writer = x.f.db.source.getConnection()) {
                    assertNotEquals(gate.session, scalar(writer, "SELECT @@SPID"), "Use distinct real SQL Server sessions.");
                    writer.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    writer.setAutoCommit(false);
                    try {
                        try (var s = writer.createStatement()) { s.setQueryTimeout(10); s.execute("SET LOCK_TIMEOUT 700"); }
                        assertEquals(700, scalar(writer, "SELECT @@LOCK_TIMEOUT"));
                        SQLException blocked = assertThrows(SQLException.class, () -> {
                            if (refund) { PaymentTestWork.cancel(x.service(), writer, x.customer, booking); }
                            else { PaymentTestWork.pay(x.service(), writer, x.customer, booking, P6Fixture.key(), "success"); }
                        });
                        assertEquals(1222, blocked.getErrorCode(), "Serializable summary reads must retain the relevant shared locks.");
                    } finally { if (!writer.isClosed()) { writer.rollback(); } }
                }
                assertEquals(before, x.snapshot(), "A blocked transition must roll back all partial financial writes.");
                gate.resume.countDown();
                var old = (StatisticsSummary.Organizer) reader.get(10, TimeUnit.SECONDS);
                assertTrue(gate.closed, "The service must close its owned read connection.");
                StatisticsIT.sales(old.sales(), refund ? 2 : 0, refund ? "20.00" : "0.00", "0.00");
                if (refund) { x.service().cancel(x.customer, booking); }
                else { x.service().pay(x.customer, booking, P6Fixture.key(), "success"); }
                var current = (StatisticsSummary.Organizer) service(x.f).summary(x.base.organizer, Role.ORGANIZER);
                StatisticsIT.sales(current.sales(), refund ? 0 : 2, "20.00", refund ? "20.00" : "0.00");
            } finally {
                gate.resume.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    /** Pauses a real JDBC read after its ticket result closes, with no production test hooks. */
    private static final class ReadGate {
        final DataSource delegate;
        final CountDownLatch afterTickets = new CountDownLatch(1), resume = new CountDownLatch(1);
        volatile long session;
        volatile boolean closed;
        ReadGate(DataSource delegate) { this.delegate = delegate; }

        DataSource source() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (!method.getName().equals("getConnection")) { return invoke(delegate, method, args); }
                Connection real = (Connection) invoke(delegate, method, args);
                session = scalar(real, "SELECT @@SPID");
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, operation, values) -> {
                    Object result = invoke(real, operation, values);
                    if (operation.getName().equals("close")) { closed = true; }
                    if (operation.getName().equals("prepareStatement")) {
                        String sql = (String) values[0];
                        assertFalse(sql.contains("sp_getapplock")); assertFalse(sql.contains("UPDLOCK"));
                        assertEquals(Connection.TRANSACTION_SERIALIZABLE, real.getTransactionIsolation());
                        assertFalse(real.getAutoCommit());
                        assertEquals(5000, scalar(real, "SELECT @@LOCK_TIMEOUT"), "The timeout must reach the actual SQL session.");
                        if (sql.startsWith("SELECT COUNT_BIG(*) FROM dbo.booking_items")) { return ticketResult((PreparedStatement) result); }
                    }
                    return result;
                });
            });
        }

        Object ticketResult(PreparedStatement real) {
            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, method, args) -> {
                Object result = invoke(real, method, args);
                if (!method.getName().equals("executeQuery")) { return result; }
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (r, operation, values) -> {
                    Object value = invoke(result, operation, values);
                    if (operation.getName().equals("close")) {
                        afterTickets.countDown();
                        assertTrue(resume.await(10, TimeUnit.SECONDS), "Release the bounded independent-session read gate.");
                    }
                    return value;
                });
            });
        }

        static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
    }
}
