package com.eventbooking.integration;

import com.eventbooking.dao.BookingDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.BookingItem;
import com.eventbooking.service.BookingService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingWaitIT {
    @Test void waitAcrossEventStartRollsBackEveryClaimAndProvisionalBooking() throws Exception { boundary(false); }
    @Test void waitAcrossOriginalHoldDeadlineRejectsWithoutExtendingIt() throws Exception { boundary(true); }

    private void boundary(boolean holdDeadline) throws Exception {
        try (P5Fixture x = new P5Fixture(2)) {
            CountDownLatch claiming = new CountDownLatch(1);
            AtomicReference<LocalDateTime> deadline = new AtomicReference<>();
            BookingDao observed = new BookingDao() {
                @Override public Draft create(Connection c, long customer, long event, String code) throws SQLException {
                    Draft created = super.create(c, customer, event, code);
                    if (!holdDeadline) { return created; }
                    // Deterministic test-owned clock fixture, retaining an exact five-minute lifetime.
                    try (var s = statement(c, "DECLARE @deadline datetime2(3)=DATEADD(SECOND,2,SYSUTCDATETIME()); UPDATE dbo.bookings SET created_at=DATEADD(MINUTE,-5,@deadline),expires_at=@deadline OUTPUT INSERTED.id,INSERTED.created_at,INSERTED.expires_at WHERE id=?", created.id()); var r = s.executeQuery()) {
                        assertTrue(r.next());
                        var adjusted = new Draft(r.getLong(1), r.getObject(2, LocalDateTime.class), r.getObject(3, LocalDateTime.class));
                        deadline.set(adjusted.expiresAt());
                        return adjusted;
                    }
                }
                @Override public BookingItem claim(Connection c, long customer, long event, Draft booking, long inventory) throws SQLException {
                    if (inventory == x.ids.get(1)) { claiming.countDown(); }
                    return super.claim(c, customer, event, booking, inventory);
                }
            };
            if (!holdDeadline) {
                x.f.access.write(c -> { update(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,3,SYSUTCDATETIME()),ends_at=DATEADD(MINUTE,3,SYSUTCDATETIME()) WHERE id=?", x.event); return null; });
                deadline.set(x.f.access.read(c -> new com.eventbooking.dao.EventDao().published(c, x.event, new com.eventbooking.dao.EventDao().now(c)).startsAt()));
            }
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                blocker.setAutoCommit(false);
                update(blocker, "UPDATE dbo.event_seats SET price=price WHERE id=?", x.ids.get(1));
                var hold = executor.submit(() -> {
                    try { return new BookingService(x.f.access, observed).hold(x.customer, x.event, x.selection(0, 1)); }
                    catch (DomainException rejected) { assertEquals(409, rejected.getStatus()); return -1L; }
                });
                try {
                    assertTrue(claiming.await(5, TimeUnit.SECONDS));
                    assertFalse(hold.isDone());
                    try (var wait = statement(blocker, "WHILE SYSUTCDATETIME()<=? WAITFOR DELAY '00:00:00.050';", deadline.get())) { wait.execute(); }
                } finally { blocker.rollback(); }
                assertEquals(-1L, hold.get(10, TimeUnit.SECONDS).longValue());
                assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items")); BookingHoldIT.available(x, 2);
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void sqlLockTimeoutReturnsControlledRetryAndRollsBackTheWholeHold() throws Exception {
        try (P5Fixture x = new P5Fixture(2)) {
            BookingDao timeout = new BookingDao() {
                @Override public BookingItem claim(Connection c, long customer, long event, Draft booking, long inventory) throws SQLException {
                    // A JDBC SQL batch sets connection state; a prepared RPC can scope SET locally.
                    try (var s = c.createStatement()) { s.setQueryTimeout(5); s.execute("SET LOCK_TIMEOUT 500"); }
                    assertEquals(500, scalar(c, "SELECT @@LOCK_TIMEOUT"));
                    return super.claim(c, customer, event, booking, inventory);
                }
            };
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                blocker.setAutoCommit(false);
                update(blocker, "UPDATE dbo.event_seats SET price=price WHERE id=?", x.ids.get(1));
                var hold = executor.submit(() -> {
                    var error = assertThrows(DomainException.class, () -> new BookingService(x.f.access, timeout).hold(x.customer, x.event, x.selection(0, 1)));
                    return error.getStatus();
                });
                try { assertEquals(409, hold.get(10, TimeUnit.SECONDS).intValue()); }
                finally { blocker.rollback(); }
                assertEquals(0, x.count("bookings")); assertEquals(0, x.count("booking_items")); BookingHoldIT.available(x, 2);
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
}
