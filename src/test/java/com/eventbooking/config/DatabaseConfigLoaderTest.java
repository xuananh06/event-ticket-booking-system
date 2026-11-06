package com.eventbooking.config;

import com.eventbooking.service.DatabaseProbe;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigLoaderTest {
    @TempDir Path directory;
    private static final String VALID = "db.host=localhost\ndb.name=file_db\ndb.user=file_user\ndb.password=unit-password\n";

    private Path file(String content) throws IOException {
        return Files.writeString(directory.resolve("database.properties"), content, StandardCharsets.UTF_8);
    }

    private Map<String, String> environment() {
        return new HashMap<>(Map.of("EVENT_DB_HOST", "environment-host", "EVENT_DB_NAME", "environment_db",
                "EVENT_DB_USER", "environment_user", "EVENT_DB_PASSWORD", "environment-password", "EVENT_DB_PORT", "1544"));
    }

    private void safe(Throwable error, String... secrets) {
        for (String secret : secrets) { assertFalse(error.toString().contains(secret), "Sensitive value in configuration diagnostic."); }
        assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
    }

    @Test void validFileUsesSecureDefaultsAndTypedSqlCredentials() throws Exception {
        var loaded = DatabaseConfigLoader.load(file(VALID).toString(), Map.of());
        assertEquals(DatabaseConfigLoader.Source.FILE, loaded.source());
        var settings = loaded.settings().orElseThrow();
        var source = (SQLServerDataSource) DataSourceFactory.create(settings);
        assertEquals("file_db", settings.databaseName()); assertEquals("file_user", source.getUser());
        assertEquals("SqlPassword", source.getAuthentication()); assertEquals(1433, source.getPortNumber());
        assertTrue(settings.encrypt()); assertFalse(source.getTrustServerCertificate());
        assertFalse(loaded.toString().contains("unit-password")); assertFalse(loaded.toString().contains("file_user"));
    }

    @Test void absentSelectionUsesEnvironmentOrRetainsNotConfigured() {
        var loaded = DatabaseConfigLoader.load(null, environment());
        assertEquals(DatabaseConfigLoader.Source.ENVIRONMENT, loaded.source());
        assertEquals("environment_db", loaded.settings().orElseThrow().databaseName());
        var empty = DatabaseConfigLoader.load(null, Map.of("EVENT_TEST_DB_CONFIRM", "test_only"));
        assertEquals(DatabaseConfigLoader.Source.NONE, empty.source()); assertTrue(empty.settings().isEmpty());
        assertEquals(DatabaseProbe.Status.NOT_CONFIGURED, new DatabaseProbe(empty.settings().map(DataSourceFactory::create)).check());
    }

    @Test void selectedFileCompletelyIgnoresEvenInvalidEnvironmentSettings() throws Exception {
        Map<String, String> input = environment();
        input.put("EVENT_DB_AUTH", "windows-integrated"); input.put("EVENT_DB_UNKNOWN", "private-environment-value");
        var loaded = DatabaseConfigLoader.load(file(VALID).toString(), input);
        var settings = loaded.settings().orElseThrow();
        assertEquals("file_db", settings.databaseName()); assertEquals("file_user", settings.value("db.user", ""));
        assertEquals("unit-password", settings.value("db.password", "")); assertEquals(1433, settings.port());
    }

    @Test void partialFileNeverBorrowsRequiredFieldsFromEnvironment() throws Exception {
        for (String key : List.of("db.host", "db.name", "db.user", "db.password")) {
            String partial = VALID.replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*\\n", "");
            var error = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(partial).toString(), environment()));
            assertTrue(error.getMessage().contains(key)); safe(error, "environment-password", "unit-password");
        }
    }

    @Test void explicitBlankRelativeMissingAndInvalidPathsFailWithoutFallback() throws Exception {
        for (String selected : new String[]{"", " ", "config/database.properties", "./database.properties", "secret-path\u0000", directory.resolve("private-missing-file").toString(), directory.toString()}) {
            var error = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(selected, environment()));
            assertTrue(error.getMessage().contains("eventbooking.db.config"));
            safe(error, "private-missing-file", "secret-path", "environment-password");
        }
        var empty = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file("").toString(), environment()));
        assertTrue(empty.getMessage().contains("db.host"));
    }

    @Test void unreadableFileHasSafeDiagnosticsAndDoesNotUseEnvironment() throws Exception {
        Path path = file(VALID);
        var error = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(path.toString(), environment(),
                ignored -> { throw new AccessDeniedException("private-path-with-password"); }));
        safe(error, "private-path-with-password", "unit-password", "environment-password");
        assertTrue(error.getMessage().contains("readable absolute file"));
    }

    @Test void utf8AndPropertiesEscapesPreserveDecodedPasswordExactly() throws Exception {
        String encoded = "db.password=\\ \\ unit-\u00e9-\ud83d\udd10\\\\folder\\tend  ";
        var settings = DatabaseConfigLoader.load(file(VALID.replace("db.password=unit-password", encoded)).toString(), Map.of()).settings().orElseThrow();
        assertEquals("  unit-\u00e9-\ud83d\udd10\\folder\tend  ", settings.value("db.password", ""));
    }

    @Test void optionalUtf8BomDoesNotBecomePartOfTheFirstKey() throws Exception {
        var settings = DatabaseConfigLoader.load(file("\uFEFF" + VALID).toString(), Map.of()).settings().orElseThrow();
        assertEquals("file_db", settings.databaseName());
    }

    @Test void malformedUtf8AndUnicodeEscapesDoNotExposeContentOrExceptionChains() throws Exception {
        Path malformed = directory.resolve("malformed.properties");
        Files.write(malformed, new byte[]{(byte) 0xc3, 0x28});
        safe(assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(malformed.toString(), environment())), "malformed.properties");
        Path escaped = file(VALID + "db.extra=private-value-" + "\\" + "uQQQQ\n");
        safe(assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(escaped.toString(), environment())), "private-value", "unit-password");
    }

    @Test void unknownAndDuplicateDecodedKeysAreRejectedWithoutPrintingValues() throws Exception {
        var unknown = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(VALID + "db.passwrod=private-value\n").toString(), Map.of()));
        assertTrue(unknown.getMessage().contains("db.passwrod")); safe(unknown, "private-value", "unit-password");
        var auth = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(VALID + "db.auth=windows-integrated\n").toString(), Map.of()));
        assertTrue(auth.getMessage().contains("SQL Server Authentication")); safe(auth, "windows-integrated");
        var duplicate = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(VALID + "db.pas" + "\\u0073" + "word=private-second-value\n").toString(), Map.of()));
        assertTrue(duplicate.getMessage().contains("Duplicate database setting: db.password")); safe(duplicate, "private-second-value", "unit-password");
        var unsafeKey = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(VALID + "private-key-content=value\n").toString(), Map.of()));
        safe(unsafeKey, "private-key-content");
    }

    @Test void invalidPortsAndBooleanVariantsFailWithOnlyTheirKey() throws Exception {
        for (String value : List.of("0", "65536", "-1", "+1", "1.5", "999999999999999999999", "1433 ")) {
            var error = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(VALID + "db.port=" + value + "\n").toString(), Map.of()));
            assertTrue(error.getMessage().contains("db.port")); safe(error, "unit-password");
        }
        for (String key : List.of("db.encrypt", "db.trustServerCertificate")) {
            for (String value : List.of("", "yes", "1", "false ", "private-boolean")) {
                var error = assertThrows(IllegalArgumentException.class, () -> DatabaseConfigLoader.load(file(VALID + key + "=" + value + "\n").toString(), Map.of()));
                assertTrue(error.getMessage().contains(key)); safe(error, "private-boolean");
            }
        }
        for (int port : new int[]{1, 65535}) {
            var settings = DatabaseConfigLoader.load(file(VALID + "db.port=" + port + "\ndb.encrypt=FALSE\ndb.trustServerCertificate=TRUE\n").toString(), Map.of()).settings().orElseThrow();
            assertEquals(port, settings.port()); assertFalse(settings.encrypt()); assertTrue(settings.trustServerCertificate());
        }
    }

    @Test void readerClosesBeforeValidationAndOnMalformedInput() throws Exception {
        Path selected = file(VALID);
        for (String content : new String[]{VALID, VALID + "db.bad=private-value\n", "db.host=\\uQQQQ"}) {
            AtomicBoolean closed = new AtomicBoolean();
            var reader = new BufferedReader(new StringReader(content)) {
                @Override public void close() throws IOException { closed.set(true); super.close(); }
            };
            try { DatabaseConfigLoader.load(selected.toString(), Map.of(), ignored -> reader); }
            catch (IllegalArgumentException expected) { safe(expected, "private-value"); }
            assertTrue(closed.get());
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void processEntryPointHonorsTheExplicitJvmSelector() throws Exception {
        String previous = System.getProperty(DatabaseConfigLoader.CONFIG_PROPERTY);
        try {
            System.setProperty(DatabaseConfigLoader.CONFIG_PROPERTY, file(VALID).toString());
            assertEquals(DatabaseConfigLoader.Source.FILE, DatabaseConfigLoader.load().source());
            assertEquals("file_db", DatabaseConfigLoader.load().settings().orElseThrow().databaseName());
            System.setProperty(DatabaseConfigLoader.CONFIG_PROPERTY, "");
            assertThrows(IllegalArgumentException.class, DatabaseConfigLoader::load);
        } finally {
            if (previous == null) { System.clearProperty(DatabaseConfigLoader.CONFIG_PROPERTY); }
            else { System.setProperty(DatabaseConfigLoader.CONFIG_PROPERTY, previous); }
        }
    }
}
