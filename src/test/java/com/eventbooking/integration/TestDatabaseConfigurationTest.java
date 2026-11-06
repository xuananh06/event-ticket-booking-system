package com.eventbooking.integration;

import com.eventbooking.config.DatabaseConfigLoader;
import com.eventbooking.config.DatabaseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseConfigurationTest {
    @TempDir Path directory;

    private DatabaseConfigLoader.Loaded environment(String database) {
        return new DatabaseConfigLoader.Loaded(DatabaseConfigLoader.Source.ENVIRONMENT, DatabaseSettings.fromEnvironment(Map.of(
                "EVENT_DB_HOST", "localhost", "EVENT_DB_NAME", database, "EVENT_DB_USER", "fixture_user", "EVENT_DB_PASSWORD", "unit-password")));
    }

    @Test void requiresExplicitMatchingConfirmationBeforeConnection() {
        var configuration = environment("fixture_database");
        assertThrows(AssertionError.class, () -> TestDatabaseConfiguration.require(null, configuration));
        assertThrows(AssertionError.class, () -> TestDatabaseConfiguration.require("", configuration));
        var mismatch = assertThrows(AssertionError.class, () -> TestDatabaseConfiguration.require("other_database", configuration));
        assertFalse(mismatch.toString().contains("fixture_database")); assertFalse(mismatch.toString().contains("other_database"));
        assertSame(configuration.settings().orElseThrow(), TestDatabaseConfiguration.require("fixture_database", configuration));
    }

    @Test void rejectsSystemDatabasesRegardlessOfCase() {
        for (String database : new String[]{"master", "MODEL", "msdb", "TempDB", " master ", "model\t"}) {
            assertThrows(AssertionError.class, () -> TestDatabaseConfiguration.require(database, environment(database)));
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void fileModeNeedsNoDuplicateEnvironmentDatabaseName() throws Exception {
        Path file = Files.writeString(directory.resolve("selected.properties"),
                "db.host=localhost\ndb.name=file_fixture\ndb.user=fixture_user\ndb.password=unit-password\n", StandardCharsets.UTF_8);
        String previous = System.getProperty(DatabaseConfigLoader.CONFIG_PROPERTY);
        try {
            System.setProperty(DatabaseConfigLoader.CONFIG_PROPERTY, file.toString());
            var loaded = DatabaseConfigLoader.load();
            assertEquals(DatabaseConfigLoader.Source.FILE, loaded.source());
            assertEquals("file_fixture", TestDatabaseConfiguration.require("file_fixture", loaded).databaseName());
        } finally {
            if (previous == null) { System.clearProperty(DatabaseConfigLoader.CONFIG_PROPERTY); }
            else { System.setProperty(DatabaseConfigLoader.CONFIG_PROPERTY, previous); }
        }
    }
}
