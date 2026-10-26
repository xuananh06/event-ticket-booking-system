package com.eventbooking.integration;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.eventbooking.config.DataSourceFactory;
import com.eventbooking.config.DatabaseSettings;
import com.eventbooking.service.DatabaseProbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real SQL Server constraints; all fixture writes are rolled back, never committed.
 */
class SqlServerFoundationIT {
    private Connection connection;
    private DataSource source;

    @BeforeEach
    void connectToExplicitlyDesignatedDatabase() throws Exception {
        Map<String, String> environment = System.getenv();
        String confirmation = environment.get("EVENT_TEST_DB_CONFIRM");
        assertNotNull(confirmation, "Set EVENT_TEST_DB_CONFIRM to the dedicated test database name.");
        assertEquals(environment.get("EVENT_DB_NAME"), confirmation, "Test database confirmation must match configuration.");
        assertFalse(java.util.Set.of("master", "model", "msdb", "tempdb").contains(confirmation.toLowerCase(java.util.Locale.ROOT)));
        source = DataSourceFactory.create(DatabaseSettings.fromEnvironment(environment).orElseThrow());
        try {
            connection = source.getConnection();
        } catch (SQLException | UnsatisfiedLinkError error) {
            fail("SQL Server test connection failed. Check local configuration and integrated-auth library; details suppressed.");
        }
        assertEquals(16, connection.getMetaData().getDatabaseMajorVersion(), "SQL Server 2022 is required.");
        assertEquals(confirmation, connection.getCatalog());
        connection.setAutoCommit(false);
        execute("SET XACT_ABORT OFF; SET ANSI_NULLS ON; SET QUOTED_IDENTIFIER ON; SET ANSI_PADDING ON; "
                + "SET ANSI_WARNINGS ON; SET CONCAT_NULL_YIELDS_NULL ON; SET ARITHABORT ON; SET NUMERIC_ROUNDABORT OFF;");
    }

    @AfterEach
    void rollbackAndClose() throws Exception {
        if (connection != null) {
            try {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            } finally {
                connection.close();
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private void rejected(String sql, int errorCode) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            SQLException error = assertThrows(SQLException.class, () -> execute(sql));
            assertEquals(errorCode, error.getErrorCode());
        } finally {
            connection.rollback(savepoint);
        }
    }

    private long event() throws SQLException {
        return scalar("SELECT MIN(id) FROM dbo.events");
    }

    private long otherEvent() throws SQLException {
        return scalar("SELECT MAX(id) FROM dbo.events");
    }

    private long inventory(long eventId) throws SQLException {
        return scalar("SELECT MIN(id) FROM dbo.event_seats WHERE event_id=" + eventId);
    }

    private long booking(long eventId) throws SQLException {
        String sql = "DECLARE @now DATETIME2(3)=SYSUTCDATETIME(); "
                + "INSERT dbo.bookings(booking_code,customer_id,event_id,total_amount,created_at,expires_at) "
                + "OUTPUT INSERTED.id VALUES (?,(SELECT id FROM dbo.users WHERE email=N'customer@example.test'),?,150000,@now,DATEADD(MINUTE,5,@now));";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "IT-" + UUID.randomUUID());
            statement.setLong(2, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private String item(long booking, long inventory, long event) {
        return "INSERT dbo.booking_items(booking_id,event_seat_id,event_id,unit_price,seat_label_snapshot) VALUES ("
                + booking + "," + inventory + "," + event + ",150000,N'A1')";
    }

    private String payment(long booking, String key, String status) {
        return "DECLARE @now DATETIME2(3)=SYSUTCDATETIME(); "
                + "INSERT dbo.payments(booking_id,idempotency_key,request_fingerprint,amount,status,created_at,succeeded_at) VALUES ("
                + booking + ",'" + key + "',REPLICATE('a',64),150000,'" + status + "',@now,"
                + (status.equals("SUCCEEDED") ? "@now" : "NULL") + ")";
    }

    @Test
    void jdbcProbeUsesRealConnectionsAndSeedUnicodeAndPasswordsRoundTrip() throws Exception {
        assertEquals(DatabaseProbe.Status.AVAILABLE, new DatabaseProbe(Optional.of(source)).check());
        assertEquals(3, scalar("SELECT COUNT(*) FROM dbo.users"));
        assertEquals(3, scalar("SELECT COUNT(DISTINCT role) FROM dbo.users"));
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT full_name,password_hash FROM dbo.users WHERE email=N'customer@example.test'")) {
            assertTrue(rows.next());
            // Existing populated databases retain their original demo/user display names.
            assertFalse(rows.getString(1).isBlank());
            assertTrue(BCrypt.verifyer().verify("DemoOnly!ChangeMe".toCharArray(), rows.getString(2)).verified);
        }
        assertEquals(8, scalar("SELECT COUNT(*) FROM dbo.seats"));
        assertEquals(16, scalar("SELECT COUNT(*) FROM dbo.event_seats"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM dbo.events WHERE starts_at>SYSUTCDATETIME()"));
        // Dedicated Unicode fixture; rolled back rather than translating any existing row.
        String unicodeName = "Nguyễn Minh Anh 🎟";
        String fixtureEmail = "unicode-" + UUID.randomUUID() + "@example.test";
        try (PreparedStatement insert = connection.prepareStatement("INSERT dbo.users(email,full_name,password_hash,role) SELECT ?,?,password_hash,'CUSTOMER' FROM dbo.users WHERE email=N'customer@example.test'")) {
            insert.setNString(1, fixtureEmail);
            insert.setNString(2, unicodeName);
            insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT full_name FROM dbo.users WHERE email=?")) {
            select.setNString(1, fixtureEmail);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(unicodeName, rows.getNString(1));
            }
        }
    }

    @Test
    void schemaContainsAllNineApplicationTablesAndTrustedConstraintsWithoutCascades() throws Exception {
        // SSMS may add sysdiagrams; require every application table without owning that metadata.
        assertEquals(9, scalar("SELECT COUNT(*) FROM sys.tables WHERE schema_id=SCHEMA_ID(N'dbo') "
                + "AND name IN ('users','categories','venues','seats','events','bookings','event_seats','booking_items','payments')"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys.foreign_keys WHERE is_disabled=1 OR is_not_trusted=1 OR delete_referential_action<>0"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys.check_constraints WHERE is_disabled=1 OR is_not_trusted=1"));
    }

    @Test
    void venueCompositeKeysRejectBothKindsOfVenueMismatch() throws Exception {
        execute("INSERT dbo.venues(name,address) VALUES (N'Other venue',N'Test address')");
        long otherVenue = scalar("SELECT MAX(id) FROM dbo.venues");
        execute("INSERT dbo.seats(venue_id,row_label,seat_number) VALUES (" + otherVenue + ",N'Z',1)");
        long seat = scalar("SELECT MAX(id) FROM dbo.seats");
        long originalVenue = scalar("SELECT venue_id FROM dbo.events WHERE id=" + event());
        rejected("INSERT dbo.event_seats(event_id,seat_id,venue_id,price) VALUES (" + event() + "," + seat + "," + originalVenue + ",1)", 547);
        rejected("INSERT dbo.event_seats(event_id,seat_id,venue_id,price) VALUES (" + event() + "," + seat + "," + otherVenue + ",1)", 547);
    }

    @Test
    void itemsAndCurrentInventoryOwnerMustMatchTheBookingEvent() throws Exception {
        long booking = booking(event());
        long otherInventory = inventory(otherEvent());
        rejected(item(booking, otherInventory, event()), 547);
        rejected(item(booking, otherInventory, otherEvent()), 547);
        rejected("UPDATE dbo.event_seats SET state='HELD',current_booking_id=" + booking
                + ",hold_expires_at=SYSUTCDATETIME() WHERE id=" + otherInventory, 547);
    }

    @Test
    void inventoryShapeAndPriceAreEnforcedIncludingNullCases() throws Exception {
        long booking = booking(event());
        long inventory = inventory(event());
        String suffix = " WHERE id=" + inventory;
        rejected("UPDATE dbo.event_seats SET state='HELD'" + suffix, 547);
        rejected("UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=" + booking + suffix, 547);
        rejected("UPDATE dbo.event_seats SET state='SOLD',current_booking_id=" + booking + ",hold_expires_at=SYSUTCDATETIME()" + suffix, 547);
        rejected("UPDATE dbo.event_seats SET price=-0.01" + suffix, 547);
        rejected("UPDATE dbo.event_seats SET state='AVAILABLE '" + suffix, 547);
        // An expired HELD row remains structurally valid and can be reused by future services.
        execute("UPDATE dbo.event_seats SET state='HELD',current_booking_id=" + booking + ",hold_expires_at=DATEADD(MINUTE,-1,SYSUTCDATETIME())" + suffix);
        execute("UPDATE dbo.event_seats SET state='SOLD',hold_expires_at=NULL" + suffix);
        execute("UPDATE dbo.event_seats SET state='AVAILABLE',current_booking_id=NULL" + suffix);
    }

    @Test
    void historicalSeatCanAppearInDifferentBookingsButNotTwiceInOneBooking() throws Exception {
        long first = booking(event());
        long second = booking(event());
        long inventory = inventory(event());
        execute(item(first, inventory, event()));
        execute(item(second, inventory, event()));
        assertEquals(2, scalar("SELECT COUNT(*) FROM dbo.booking_items WHERE event_seat_id=" + inventory));
        rejected(item(first, inventory, event()), 2627);
        rejected("DELETE FROM dbo.bookings WHERE id=" + first, 547);
        rejected("DELETE FROM dbo.event_seats WHERE id=" + inventory, 547);
    }

    @Test
    void paymentKeysAreScopedAndRefundNeverPermitsAnotherSuccess() throws Exception {
        long first = booking(event());
        long second = booking(event());
        execute(payment(first, "attempt-1", "FAILED"));
        rejected(payment(first, "attempt-1", "FAILED"), 2627);
        execute(payment(second, "attempt-1", "FAILED"));
        execute(payment(first, "attempt-2", "SUCCEEDED"));
        execute("UPDATE dbo.payments SET status='REFUNDED',refunded_at=SYSUTCDATETIME() WHERE booking_id=" + first + " AND status='SUCCEEDED'");
        rejected(payment(first, "attempt-3", "SUCCEEDED"), 2601);
        execute(payment(first, "attempt-4", "FAILED"));
        rejected("UPDATE dbo.payments SET status='SUCCEEDED' WHERE booking_id=" + second, 547);
        rejected("UPDATE dbo.payments SET request_fingerprint=REPLICATE('z',64) WHERE booking_id=" + second, 547);
        rejected("DELETE FROM dbo.bookings WHERE id=" + first, 547);
    }

    @Test
    void normalizedEmailUniqueInventoryAndBusinessShapesAreEnforced() throws Exception {
        long booking = booking(event());
        rejected("UPDATE dbo.users SET email=N'CUSTOMER@example.test' WHERE email=N'customer@example.test'", 547);
        rejected("UPDATE dbo.users SET email=N'customer@example.test ' WHERE email=N'customer@example.test'", 547);
        rejected("UPDATE dbo.users SET email=N'customer@example.test' WHERE email=N'organizer@example.test'", 2627);
        rejected("UPDATE dbo.users SET role='ROOT' WHERE email=N'customer@example.test'", 547);
        rejected("UPDATE dbo.users SET password_hash=password_hash+' ' WHERE email=N'customer@example.test'", 547);
        rejected("INSERT dbo.event_seats(event_id,seat_id,venue_id,price) SELECT event_id,seat_id,venue_id,price FROM dbo.event_seats WHERE id=" + inventory(event()), 2627);
        rejected("UPDATE dbo.events SET ends_at=starts_at WHERE id=" + event(), 547);
        rejected("UPDATE dbo.bookings SET total_amount=-1 WHERE id=" + booking, 547);
        rejected("UPDATE dbo.bookings SET status='CONFIRMED' WHERE id=" + booking, 547);
        rejected("UPDATE dbo.bookings SET expires_at=DATEADD(MINUTE,6,created_at) WHERE id=" + booking, 547);
    }

    @Test
    void schemaAndSeedRerunsAreRefusedWithoutChangingSeedRows() throws Exception {
        // Scripts own transactions. Run rerun checks on independent autocommit connections.
        for (String file : new String[]{"schema.sql", "seed.sql"}) {
            try (Connection isolated = source.getConnection(); Statement statement = isolated.createStatement()) {
                statement.setQueryTimeout(10);
                SQLException error = assertThrows(SQLException.class,
                        () -> statement.execute(Files.readString(Path.of("database", file))));
                assertEquals(file.equals("schema.sql") ? 51001 : 51002, error.getErrorCode());
            }
        }
        assertEquals(3, scalar("SELECT COUNT(*) FROM dbo.users"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM dbo.bookings"));
        assertEquals(16, scalar("SELECT COUNT(*) FROM dbo.event_seats"));
    }
}
