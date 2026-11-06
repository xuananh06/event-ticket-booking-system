package com.eventbooking.integration;

import com.eventbooking.dao.PaymentDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Booking;
import com.eventbooking.service.PaymentService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentBoundaryIT {
    @Test void successfulPaymentWaitingOnFinalSeatCannotCrossOriginalHoldExpiry() throws Exception {
        boundary(true, "success");
    }

    @Test void failedPaymentWaitingOnFinalSeatCannotCrossOriginalHoldExpiry() throws Exception {
        boundary(true, "failure");
    }

    @Test void successfulPaymentWaitingOnFinalSeatCannotCrossEventStart() throws Exception {
        boundary(false, "success");
    }

    @Test void failedPaymentWaitingOnFinalSeatCannotCrossEventStart() throws Exception {
        boundary(false, "failure");
    }

    private void boundary(boolean holdExpiry, String outcome) throws Exception {
        try (P5Fixture x = new P5Fixture(2)) {
            long booking = x.service().hold(x.customer, x.event, x.selection(0, 1));
            CountDownLatch waiting = new CountDownLatch(1);
            AtomicInteger acquired = new AtomicInteger();
            AtomicLong paymentSession = new AtomicLong();
            PaymentDao observed = observing(x, waiting, acquired, paymentSession, null);
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                long blockerSession = scalar(blocker, "SELECT @@SPID");
                // The fixture uses SQL Server's clock and retains the original five-minute shape.
                LocalDateTime deadline = deadline(x, booking, holdExpiry);
                List<String> before = snapshot(x);
                blocker.setAutoCommit(false);
                blockFinalSeat(blocker, x);
                var payment = executor.submit(() -> assertThrows(DomainException.class,
                        () -> new PaymentService(x.f.access, x.dao, observed)
                                .pay(x.customer, booking, UUID.randomUUID().toString(), outcome)));
                try {
                    assertTrue(waiting.await(5, TimeUnit.SECONDS), "The payment must reach its final inventory lock.");
                    assertEquals(1, acquired.get(), "The first inventory row must already be locked.");
                    assertNotEquals(blockerSession, paymentSession.get(), "The blocker and payment need independent SQL sessions.");
                    assertFalse(payment.isDone(), "The final inventory lock must still be blocked.");
                    assertEquals(1, scalar(blocker, "SELECT CASE WHEN SYSUTCDATETIME()<? THEN 1 ELSE 0 END", deadline),
                            "The final-seat wait must begin before the SQL-time boundary.");
                    try (var wait = statement(blocker,
                            "WHILE SYSUTCDATETIME()<=? WAITFOR DELAY '00:00:00.050';", deadline)) {
                        wait.execute();
                    }
                } finally {
                    blocker.rollback();
                }
                assertEquals(409, payment.get(10, TimeUnit.SECONDS).getStatus());
                assertUnchanged(x, booking, before);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void successfulPaymentLockTimeoutIsControlledAndRollsBackTheWholeAttempt() throws Exception {
        timeout("success");
    }

    @Test void failedPaymentLockTimeoutIsControlledAndRollsBackTheWholeAttempt() throws Exception {
        timeout("failure");
    }

    private void timeout(String outcome) throws Exception {
        try (P5Fixture x = new P5Fixture(2)) {
            long booking = x.service().hold(x.customer, x.event, x.selection(0, 1));
            List<String> before = snapshot(x);
            CountDownLatch waiting = new CountDownLatch(1);
            AtomicInteger acquired = new AtomicInteger(), sqlError = new AtomicInteger();
            AtomicLong paymentSession = new AtomicLong();
            PaymentDao observed = observing(x, waiting, acquired, paymentSession, sqlError);
            var executor = Executors.newSingleThreadExecutor();
            try (Connection blocker = x.f.db.source.getConnection()) {
                long blockerSession = scalar(blocker, "SELECT @@SPID");
                blocker.setAutoCommit(false);
                blockFinalSeat(blocker, x);
                var payment = executor.submit(() -> assertThrows(DomainException.class,
                        () -> new PaymentService(x.f.access, x.dao, observed)
                                .pay(x.customer, booking, UUID.randomUUID().toString(), outcome)));
                try {
                    assertTrue(waiting.await(5, TimeUnit.SECONDS));
                    assertEquals(1, acquired.get());
                    assertNotEquals(blockerSession, paymentSession.get());
                    // Complete while the blocker remains held, proving a real SQL lock timeout.
                    var rejected = payment.get(10, TimeUnit.SECONDS);
                    assertEquals(1222, sqlError.get());
                    assertEquals(409, rejected.getStatus());
                    assertTrue(rejected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("retry"));
                    assertFalse(rejected.getMessage().contains("java.sql"));
                    assertFalse(rejected.getMessage().contains("1222"));
                } finally {
                    blocker.rollback();
                }
                assertUnchanged(x, booking, before);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private PaymentDao observing(P5Fixture x, CountDownLatch waiting, AtomicInteger acquired,
                                 AtomicLong paymentSession, AtomicInteger sqlError) {
        return new PaymentDao() {
            @Override public void lockSeat(Connection c, Booking booking, long inventory,
                                           String state, boolean physical) throws SQLException {
                paymentSession.compareAndSet(0, scalar(c, "SELECT @@SPID"));
                if (inventory == x.ids.get(1)) {
                    if (sqlError != null) {
                        // Use a SQL batch: SET inside a prepared RPC can be scoped to that RPC.
                        try (var set = c.createStatement()) {
                            set.setQueryTimeout(5);
                            set.execute("SET LOCK_TIMEOUT 500");
                        }
                        assertEquals(500, scalar(c, "SELECT @@LOCK_TIMEOUT"));
                    }
                    waiting.countDown();
                }
                try {
                    super.lockSeat(c, booking, inventory, state, physical);
                    acquired.incrementAndGet();
                } catch (SQLException failed) {
                    if (sqlError != null) { sqlError.set(failed.getErrorCode()); }
                    throw failed;
                }
            }
        };
    }

    private LocalDateTime deadline(P5Fixture x, long booking, boolean holdExpiry) {
        if (holdExpiry) { return x.deadline(booking, 4); }
        return x.f.access.write(c -> {
            try (var s = statement(c, "UPDATE dbo.events SET starts_at=DATEADD(SECOND,4,SYSUTCDATETIME()),"
                    + "ends_at=DATEADD(MINUTE,3,SYSUTCDATETIME()) OUTPUT INSERTED.starts_at WHERE id=?", x.event);
                 var r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getObject(1, LocalDateTime.class);
            }
        });
    }

    private void blockFinalSeat(Connection blocker, P5Fixture x) throws SQLException {
        // U permits the earlier allocation S scan, but blocks the production per-seat U lock.
        assertEquals(x.ids.get(1).longValue(), scalar(blocker,
                "SELECT id FROM dbo.event_seats WITH (UPDLOCK,HOLDLOCK,ROWLOCK) WHERE id=?", x.ids.get(1)));
    }

    private void assertUnchanged(P5Fixture x, long booking, List<String> before) {
        assertEquals(before, snapshot(x), "Rejected payment must preserve booking, items, attempts and inventory exactly.");
        assertEquals(1, x.count("bookings"));
        assertEquals(2, x.count("booking_items"));
        x.f.access.read(c -> {
            assertEquals(0, scalar(c, "SELECT COUNT_BIG(*) FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id WHERE b.event_id=?", x.event));
            assertEquals(1, scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings WHERE id=? AND event_id=? AND status='PENDING' AND confirmed_at IS NULL AND cancelled_at IS NULL", booking, x.event));
            assertEquals(2, scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats es JOIN dbo.bookings b ON b.id=es.current_booking_id WHERE b.id=? AND es.event_id=? AND es.state='HELD' AND es.hold_expires_at=b.expires_at", booking, x.event));
            return null;
        });
    }

    private List<String> snapshot(P5Fixture x) {
        return x.f.access.read(c -> List.of(
                digest(c, "SELECT b.id,b.booking_code,b.customer_id,b.event_id,b.status,b.total_amount,b.created_at,b.expires_at,b.confirmed_at,b.cancelled_at FROM dbo.bookings b WHERE b.event_id=? ORDER BY b.id", x.event),
                digest(c, "SELECT bi.id,bi.booking_id,bi.event_seat_id,bi.event_id,bi.unit_price,bi.seat_label_snapshot FROM dbo.booking_items bi WHERE bi.event_id=? ORDER BY bi.id", x.event),
                digest(c, "SELECT p.id,p.booking_id,p.idempotency_key,p.request_fingerprint,p.amount,p.status,p.created_at,p.succeeded_at,p.refunded_at FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id WHERE b.event_id=? ORDER BY p.id", x.event),
                digest(c, "SELECT es.id,es.event_id,es.seat_id,es.venue_id,es.price,es.state,es.current_booking_id,es.hold_expires_at FROM dbo.event_seats es WHERE es.event_id=? ORDER BY es.id", x.event)));
    }

    private String digest(Connection c, String projection, long event) throws SQLException {
        try (var s = statement(c, "SELECT CONVERT(varchar(64),HASHBYTES('SHA2_256',COALESCE((" + projection
                + " FOR JSON PATH,INCLUDE_NULL_VALUES),N'[]')),2)", event); var r = s.executeQuery()) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }
}
