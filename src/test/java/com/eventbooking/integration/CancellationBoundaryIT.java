package com.eventbooking.integration;

import com.eventbooking.dao.PaymentDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Booking;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class CancellationBoundaryIT {
    @Test void pendingCancellationCannotCrossHoldExpiryAtTheFinalSeatLock() throws Exception { boundary(false, true); }
    @Test void pendingCancellationCannotCrossEventStartAtTheFinalSeatLock() throws Exception { boundary(false, false); }
    @Test void confirmedRefundCannotCrossEventStartAtTheFinalSeatLock() throws Exception { boundary(true, false); }
    @Test void pendingCancellationLockTimeoutIsControlledAndAtomic() throws Exception { timeout(false); }
    @Test void confirmedRefundLockTimeoutIsControlledAndAtomic() throws Exception { timeout(true); }

    private void boundary(boolean confirmed, boolean hold) throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1);
            if (confirmed) { x.service().pay(x.customer, id, P6Fixture.key(), "success"); }
            LocalDateTime deadline = hold ? x.deadline(id, 4) : x.f.access.write(c -> {
                try (var s = statement(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,4,SYSUTCDATETIME()),ends_at=DATEADD(MINUTE,3,SYSUTCDATETIME()) OUTPUT INSERTED.starts_at WHERE id=?", x.event); var r = s.executeQuery()) {
                    assertTrue(r.next()); return r.getObject(1, LocalDateTime.class);
                }
            });
            var before = x.snapshot(); CountDownLatch waiting = new CountDownLatch(1); AtomicLong session = new AtomicLong(); AtomicInteger acquired = new AtomicInteger();
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                blocker.setAutoCommit(false);
                long blockerSession = scalar(blocker, "SELECT @@SPID");
                assertEquals(x.ids.get(1).longValue(), scalar(blocker, "SELECT id FROM dbo.event_seats WITH(UPDLOCK,HOLDLOCK,ROWLOCK) WHERE id=?", x.ids.get(1)));
                var action = executor.submit(() -> assertThrows(DomainException.class, () -> x.service(observe(x, waiting, session, acquired, null)).cancel(x.customer, id)));
                try {
                    assertTrue(waiting.await(5, TimeUnit.SECONDS)); assertEquals(1, acquired.get()); assertNotEquals(blockerSession, session.get());
                    assertFalse(action.isDone());
                    assertEquals(1, scalar(blocker, "SELECT CASE WHEN SYSUTCDATETIME()<? THEN 1 ELSE 0 END", deadline));
                    try (var s = statement(blocker, "WHILE SYSUTCDATETIME()<=? WAITFOR DELAY '00:00:00.050';", deadline)) { s.execute(); }
                } finally { blocker.rollback(); }
                assertEquals(409, action.get(10, TimeUnit.SECONDS).getStatus()); assertEquals(before, x.snapshot());
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private void timeout(boolean confirmed) throws Exception {
        try (P6Fixture x = new P6Fixture(2)) {
            long id = x.hold(0, 1); if (confirmed) { x.service().pay(x.customer, id, P6Fixture.key(), "success"); }
            var before = x.snapshot(); CountDownLatch waiting = new CountDownLatch(1); AtomicLong session = new AtomicLong(); AtomicInteger acquired = new AtomicInteger(), error = new AtomicInteger();
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                blocker.setAutoCommit(false);
                long blockerSession = scalar(blocker, "SELECT @@SPID");
                scalar(blocker, "SELECT id FROM dbo.event_seats WITH(UPDLOCK,HOLDLOCK,ROWLOCK) WHERE id=?", x.ids.get(1));
                var action = executor.submit(() -> assertThrows(DomainException.class, () -> x.service(observe(x, waiting, session, acquired, error)).cancel(x.customer, id)));
                try {
                    assertTrue(waiting.await(5, TimeUnit.SECONDS)); assertEquals(1, acquired.get()); assertNotEquals(blockerSession, session.get());
                    assertEquals(409, action.get(10, TimeUnit.SECONDS).getStatus()); assertEquals(1222, error.get());
                } finally { blocker.rollback(); }
                assertEquals(before, x.snapshot());
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private PaymentDao observe(P6Fixture x, CountDownLatch waiting, AtomicLong session, AtomicInteger acquired, AtomicInteger error) {
        return new PaymentDao() {
            @Override public void lockSeat(Connection c, Booking b, long inventory, String state, boolean physical) throws SQLException {
                session.compareAndSet(0, scalar(c, "SELECT @@SPID"));
                if (inventory == x.ids.get(1)) {
                    if (error != null) {
                        try (var set = c.createStatement()) { set.setQueryTimeout(5); set.execute("SET LOCK_TIMEOUT 500"); }
                        assertEquals(500, scalar(c, "SELECT @@LOCK_TIMEOUT"));
                    }
                    waiting.countDown();
                }
                try { super.lockSeat(c, b, inventory, state, physical); acquired.incrementAndGet(); }
                catch (SQLException failed) { if (error != null) { error.set(failed.getErrorCode()); } throw failed; }
            }
        };
    }
}
