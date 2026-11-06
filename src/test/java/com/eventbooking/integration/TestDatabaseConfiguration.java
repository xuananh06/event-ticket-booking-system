package com.eventbooking.integration;

import com.eventbooking.config.DatabaseConfigLoader;
import com.eventbooking.config.DatabaseSettings;
import java.util.Locale;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Shared source selection and dedicated-database protection before any fixture connection. */
final class TestDatabaseConfiguration {
    private TestDatabaseConfiguration() { }

    static DatabaseSettings load() {
        return require(System.getenv("EVENT_TEST_DB_CONFIRM"), DatabaseConfigLoader.load());
    }

    static DatabaseSettings require(String confirmation, DatabaseConfigLoader.Loaded configuration) {
        assertNotNull(confirmation, "Set EVENT_TEST_DB_CONFIRM to the dedicated test database name.");
        DatabaseSettings settings = configuration.settings().orElseThrow(
                () -> new AssertionError("Select a complete SQL Server test database configuration."));
        assertTrue(confirmation.equals(settings.databaseName()), "Test database confirmation must match the resolved configuration.");
        assertFalse(Set.of("master", "model", "msdb", "tempdb").contains(settings.databaseName().strip().toLowerCase(Locale.ROOT)),
                "System databases cannot be used for integration tests.");
        return settings;
    }
}
