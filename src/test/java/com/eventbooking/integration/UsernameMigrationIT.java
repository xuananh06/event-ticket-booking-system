package com.eventbooking.integration;

import com.eventbooking.config.DataSourceFactory;
import com.eventbooking.security.PasswordHasher;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real migration and fresh-install checks; only exact-owned schemas contain fixture rows. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UsernameMigrationIT {
    private static final List<String> TABLES = List.of("users", "categories", "venues", "seats", "events",
            "bookings", "event_seats", "booking_items", "payments");
    private DataSource source;

    @BeforeEach
    void requireDedicatedSqlServer() throws Exception {
        var settings = TestDatabaseConfiguration.load();
        source = DataSourceFactory.create(settings);
        try (Connection c = source.getConnection()) {
            assertEquals(settings.databaseName(), c.getCatalog(), "Actual catalog must match the confirmed test database.");
            assertEquals(16, c.getMetaData().getDatabaseMajorVersion(), "SQL Server 2022 is required.");
        } catch (SQLException error) {
            fail("SQL migration verification cannot connect; private connection details suppressed.");
        }
    }

    @Test
    @Order(1)
    void existingDatabaseMigrationPreservesEveryOriginalColumnAndIsSafeToRepeat() throws Exception {
        try (Connection c = source.getConnection()) {
            var originalColumns = columns(c, "dbo");
            var before = snapshot(c, "dbo", originalColumns);
            boolean legacy = !originalColumns.get("users").contains("username");
            script(c, "dbo", "migrations/001_add_usernames.sql");
            assertEquals(before, snapshot(c, "dbo", originalColumns), "Migration changed pre-existing application values.");
            if (legacy) {
                assertEquals(before.get("users").count(), scalar(c, "SELECT COUNT_BIG(*) FROM dbo.users WHERE username IS NULL"),
                        "Migration must leave every legacy username unassigned.");
            }
            var migratedColumns = columns(c, "dbo");
            var migrated = snapshot(c, "dbo", migratedColumns);
            script(c, "dbo", "migrations/001_add_usernames.sql");
            assertEquals(migrated, snapshot(c, "dbo", migratedColumns), "Repeated migration changed application rows.");
            assertContract(c, "dbo");
            System.out.println("SQL username migration: original nine-table values preserved; repeat unchanged.");
        }
    }

    @Test
    void populatedLegacyGraphSurvivesMigrationAndBothScriptRefusals() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fresh();
            execute(f.c, "DROP INDEX UX_users_username ON " + f.table("users"));
            execute(f.c, "ALTER TABLE " + f.table("users") + " DROP CONSTRAINT CK_users_username");
            execute(f.c, "ALTER TABLE " + f.table("users") + " DROP COLUMN username");
            execute(f.c, ("DECLARE @customer bigint=(SELECT id FROM dbo.users WHERE role='CUSTOMER');"
                    + "DECLARE @event bigint=(SELECT id FROM dbo.events WHERE status='PUBLISHED');"
                    + "DECLARE @now datetime2(3)=SYSUTCDATETIME();"
                    + "INSERT dbo.bookings(booking_code,customer_id,event_id,status,total_amount,created_at,expires_at,confirmed_at)"
                    + " VALUES ('legacy-migration',@customer,@event,'CONFIRMED',300000.00,@now,DATEADD(MINUTE,5,@now),@now);"
                    + "DECLARE @booking bigint=CONVERT(bigint,SCOPE_IDENTITY());"
                    + "INSERT dbo.booking_items(booking_id,event_seat_id,event_id,unit_price,seat_label_snapshot)"
                    + " SELECT TOP(2) @booking,id,event_id,price,N'Legacy seat' FROM dbo.event_seats WHERE event_id=@event ORDER BY id;"
                    + "UPDATE dbo.event_seats SET state='SOLD',current_booking_id=@booking WHERE id IN"
                    + " (SELECT event_seat_id FROM dbo.booking_items WHERE booking_id=@booking);"
                    + "INSERT dbo.payments(booking_id,idempotency_key,request_fingerprint,amount,status,created_at,succeeded_at)"
                    + " VALUES (@booking,'legacy-payment',REPLICATE('a',64),300000.00,'SUCCEEDED',@now,@now);").replace("dbo.", f.schema + "."));
            var originalColumns = columns(f.c, f.schema);
            var before = snapshot(f.c, f.schema, originalColumns);
            assertEquals(1, before.get("bookings").count());
            assertEquals(2, before.get("booking_items").count());
            assertEquals(1, before.get("payments").count());
            for (String file : List.of("schema.sql", "seed.sql")) {
                SQLException error = assertThrows(SQLException.class, () -> script(f.c, f.schema, file));
                assertEquals(file.equals("schema.sql") ? 51001 : 51002, error.getErrorCode());
                assertEquals(before, snapshot(f.c, f.schema, originalColumns), "Refused pre-migration rerun changed legacy values: " + file);
            }
            script(f.c, f.schema, "migrations/001_add_usernames.sql");
            assertEquals(before, snapshot(f.c, f.schema, originalColumns), "Legacy graph changed during migration.");
            assertEquals(3, scalar(f.c, "SELECT COUNT_BIG(*) FROM " + f.table("users") + " WHERE username IS NULL"));
            var allColumns = columns(f.c, f.schema);
            var migrated = snapshot(f.c, f.schema, allColumns);
            script(f.c, f.schema, "migrations/001_add_usernames.sql");
            assertEquals(migrated, snapshot(f.c, f.schema, allColumns), "Repeat changed migrated legacy rows.");
            for (String file : List.of("schema.sql", "seed.sql")) {
                SQLException error = assertThrows(SQLException.class, () -> script(f.c, f.schema, file));
                assertEquals(file.equals("schema.sql") ? 51001 : 51002, error.getErrorCode());
                assertEquals(migrated, snapshot(f.c, f.schema, allColumns), "Refused rerun changed fixture graph: " + file);
            }
        }
    }

    @Test
    void freshInstallSeedsDistinctUsernamesAndRetainsValidPasswordHashes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fresh();
            assertContract(f.c, f.schema);
            try (var s = f.c.createStatement(); var r = s.executeQuery("SELECT username,role,password_hash FROM " + f.table("users"))) {
                Set<String> names = new HashSet<>();
                while (r.next()) {
                    assertEquals(r.getString("role").toLowerCase(Locale.ROOT), r.getNString("username"));
                    assertTrue(names.add(r.getNString("username")), "Demo usernames must be distinct.");
                    assertTrue(new PasswordHasher().matches("DemoOnly!ChangeMe", r.getString("password_hash")), "Demo password hash must remain valid.");
                }
                assertEquals(Set.of("admin", "organizer", "customer"), names);
            }
            var cols = columns(f.c, f.schema); var before = snapshot(f.c, f.schema, cols);
            script(f.c, f.schema, "migrations/001_add_usernames.sql");
            assertEquals(before, snapshot(f.c, f.schema, cols), "Migration on a fresh schema must preserve rows.");
        }
    }

    @Test
    void storedUsernamesEnforceLowercaseAsciiLengthAndFilteredUniqueness() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fresh();
            execute(f.c, "SET NOCOUNT OFF");
            execute(f.c, "UPDATE " + f.table("users") + " SET username=NULL");
            assertEquals(3, scalar(f.c, "SELECT COUNT_BIG(*) FROM " + f.table("users") + " WHERE username IS NULL"));
            long first = scalar(f.c, "SELECT MIN(id) FROM " + f.table("users"));
            long second = scalar(f.c, "SELECT MAX(id) FROM " + f.table("users"));
            for (String invalid : List.of("", "ab", "ABC", " abc", "abc ", "a@b", "a b", "a/b", "a\\b", "a'b", "a\nb", "ab\u0000", "caf\u00e9", "\u212aelvin")) {
                try (var s = f.c.prepareStatement("UPDATE " + f.table("users") + " SET username=? WHERE id=?")) {
                    s.setNString(1, invalid); s.setLong(2, first);
                    SQLException error = assertThrows(SQLException.class, s::executeUpdate);
                    assertEquals(547, error.getErrorCode(), "Invalid stored username must fail its check constraint.");
                }
                assertEquals(3, scalar(f.c, "SELECT COUNT_BIG(*) FROM " + f.table("users") + " WHERE username IS NULL"));
            }
            for (String valid : List.of("abc", "a.-_09", "._-", "-ab", "x".repeat(30))) {
                try (var s = f.c.prepareStatement("UPDATE " + f.table("users") + " SET username=? WHERE id=?")) {
                    s.setNString(1, valid); s.setLong(2, first); assertEquals(1, s.executeUpdate());
                }
                try (var s = f.c.prepareStatement("SELECT username FROM " + f.table("users") + " WHERE id=?")) {
                    s.setLong(1, first); try (var r = s.executeQuery()) { assertTrue(r.next()); assertEquals(valid, r.getNString(1)); }
                }
            }
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> execute(f.c, "UPDATE " + f.table("users") + " SET username=REPLICATE(N'x',30) WHERE id=" + second));
            assertEquals(2601, duplicate.getErrorCode(), "Filtered unique index must reject duplicate usernames.");
            try (var s = f.c.prepareStatement("UPDATE " + f.table("users") + " SET username=? WHERE id=?")) {
                s.setNString(1, "x".repeat(31)); s.setLong(2, first);
                SQLException length = assertThrows(SQLException.class, s::executeUpdate);
                assertTrue(Set.of(2628, 8152).contains(length.getErrorCode()), "SQL length enforcement must reject oversized usernames.");
            }
        }
    }

    private void assertContract(Connection c, String schema) throws SQLException {
        assertEquals(1, scalar(c, "SELECT COUNT_BIG(*) FROM sys.columns WHERE object_id=OBJECT_ID('" + schema + ".users')"
                + " AND name='username' AND system_type_id=231 AND max_length=60 AND is_nullable=1 AND collation_name='Latin1_General_100_BIN2'"));
        assertEquals(1, scalar(c, "SELECT COUNT_BIG(*) FROM sys.check_constraints WHERE parent_object_id=OBJECT_ID('" + schema + ".users')"
                + " AND name='CK_users_username' AND is_disabled=0 AND is_not_trusted=0"));
        assertEquals(1, scalar(c, "SELECT COUNT_BIG(*) FROM sys.indexes WHERE object_id=OBJECT_ID('" + schema + ".users')"
                + " AND name='UX_users_username' AND is_unique=1 AND has_filter=1 AND is_disabled=0"));
    }

    private record Snapshot(long count, String digest) { }

    private Map<String, List<String>> columns(Connection c, String schema) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<String> names = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT name FROM sys.columns WHERE object_id=OBJECT_ID(?) ORDER BY column_id")) {
                s.setNString(1, schema + "." + table);
                try (var r = s.executeQuery()) { while (r.next()) names.add(r.getNString(1)); }
            }
            assertFalse(names.isEmpty(), "Application table columns must exist: " + table);
            result.put(table, List.copyOf(names));
        }
        return result;
    }

    private Map<String, Snapshot> snapshot(Connection c, String schema, Map<String, List<String>> columns) throws SQLException {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        for (String table : TABLES) {
            String relation = "[" + schema + "].[" + table + "]";
            String projection = columns.get(table).stream().map(n -> "[" + n.replace("]", "]]") + "]").collect(java.util.stream.Collectors.joining(","));
            try (var s = c.createStatement()) {
                s.setQueryTimeout(10);
                try (var r = s.executeQuery("SELECT COUNT_BIG(*),CONVERT(varchar(64),HASHBYTES('SHA2_256',COALESCE((SELECT "
                        + projection + " FROM " + relation + " ORDER BY id FOR JSON PATH,INCLUDE_NULL_VALUES),N'[]')),2) FROM " + relation)) {
                    assertTrue(r.next(), "Missing table snapshot: " + table);
                    String digest = r.getString(2); assertNotNull(digest, "Missing table digest: " + table);
                    result.put(table, new Snapshot(r.getLong(1), digest));
                }
            }
        }
        return result;
    }

    private void script(Connection c, String schema, String name) throws Exception {
        String sql = Files.readString(Path.of("database", name)).replace("dbo.", schema + ".").replace("N'dbo'", "N'" + schema + "'");
        execute(c, sql);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) { s.setQueryTimeout(30); s.execute(sql); }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) { s.setQueryTimeout(10); try (var r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getLong(1); } }
    }

    private final class Fixture implements AutoCloseable {
        final String schema = "username_" + UUID.randomUUID().toString().replace("-", "");
        final Connection c;

        Fixture() throws SQLException {
            c = source.getConnection();
            try { execute(c, "CREATE SCHEMA [" + schema + "]"); }
            catch (SQLException error) { c.close(); throw error; }
        }

        String table(String table) { return "[" + schema + "].[" + table + "]"; }

        void fresh() throws Exception { script(c, schema, "schema.sql"); script(c, schema, "seed.sql"); }

        @Override public void close() throws SQLException {
            try {
                for (String table : List.of("payments", "booking_items", "event_seats", "bookings", "events", "seats", "venues", "categories", "users")) {
                    execute(c, "DROP TABLE IF EXISTS " + table(table));
                }
                execute(c, "DROP SCHEMA [" + schema + "]");
            } finally { c.close(); }
        }
    }
}
