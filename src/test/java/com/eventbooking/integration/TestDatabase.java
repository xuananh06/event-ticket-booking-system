package com.eventbooking.integration;

import com.eventbooking.config.DataSourceFactory;
import com.eventbooking.config.DatabaseSettings;
import com.eventbooking.dao.UserDao;
import com.eventbooking.security.PasswordHasher;
import com.eventbooking.service.AuthService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Committed authentication fixtures have unique, explicitly owned email addresses.
 */
final class TestDatabase implements AutoCloseable {
    final DataSource source;
    final AuthService auth;
    private final String prefix = "auth-" + UUID.randomUUID() + "-";
    private final List<String> ownedEmails = new ArrayList<>();

    TestDatabase() throws Exception {
        var environment = System.getenv();
        String confirmation = environment.get("EVENT_TEST_DB_CONFIRM");
        assertNotNull(confirmation, "Set EVENT_TEST_DB_CONFIRM to a dedicated test database.");
        assertEquals(environment.get("EVENT_DB_NAME"), confirmation, "Test database confirmation must match configuration.");
        assertFalse(Set.of("master", "model", "msdb", "tempdb").contains(confirmation.toLowerCase(Locale.ROOT)));
        source = DataSourceFactory.create(DatabaseSettings.fromEnvironment(environment).orElseThrow());
        try (Connection connection = source.getConnection()) {
            assertEquals(confirmation, connection.getCatalog());
            assertEquals(16, connection.getMetaData().getDatabaseMajorVersion(), "SQL Server 2022 is required.");
        } catch (SQLException | UnsatisfiedLinkError error) {
            fail("Test database connection failed; connection details suppressed.");
        }
        auth = new AuthService(new UserDao(Optional.of(source)), new PasswordHasher());
    }

    String email() {
        String email = prefix + ownedEmails.size() + "@example.test";
        ownedEmails.add(email);
        return email;
    }

    void changeAccount(String email, String role, boolean active) throws SQLException {
        requireOwned(email);
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE dbo.users SET role=?,is_active=? WHERE email=?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, role);
            statement.setBoolean(2, active);
            statement.setNString(3, email);
            assertEquals(1, statement.executeUpdate(), "Only a test-owned account may be changed.");
        }
    }

    long count(String email) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT_BIG(*) FROM dbo.users WHERE email=?")) {
            statement.setQueryTimeout(5);
            statement.setNString(1, email);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private void requireOwned(String email) {
        if (!email.startsWith(prefix) || !ownedEmails.contains(email)) {
            throw new IllegalArgumentException("Refusing to modify an account outside this test's fixtures.");
        }
    }

    @Override
    public void close() throws SQLException {
        if (ownedEmails.isEmpty()) {
            return;
        }
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM dbo.users WHERE email=?")) {
            statement.setQueryTimeout(5);
            for (String email : ownedEmails) {
                requireOwned(email);
                statement.setNString(1, email);
                statement.executeUpdate();
            }
        }
    }
}
