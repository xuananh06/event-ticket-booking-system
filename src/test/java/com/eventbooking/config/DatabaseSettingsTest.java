package com.eventbooking.config;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSettingsTest {
    private Map<String, String> valid() {
        return new HashMap<>(Map.of("EVENT_DB_HOST", "localhost", "EVENT_DB_NAME", "test_db",
                "EVENT_DB_USER", "test_user", "EVENT_DB_PASSWORD", "secret-for-unit-test"));
    }

    @Test
    void absenceIsExplicitButPartialConfigurationIsRejected() {
        assertTrue(DatabaseSettings.fromEnvironment(Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseSettings.fromEnvironment(Map.of("EVENT_DB_HOST", "localhost")));
    }

    @Test
    void appliesSecureDefaultsAndPreservesPasswordWhitespace() {
        Map<String, String> input = valid();
        input.put("EVENT_DB_PASSWORD", "  password spaces  ");
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(input).orElseThrow();
        input.put("EVENT_DB_HOST", "changed-after-construction");
        SQLServerDataSource source = (SQLServerDataSource) DataSourceFactory.create(settings);
        assertEquals("localhost", source.getServerName());
        assertEquals("test_db", source.getDatabaseName());
        assertEquals(1433, source.getPortNumber());
        assertEquals("  password spaces  ", settings.value("db.password", ""));
        assertEquals("test_user", source.getUser());
        assertEquals("SqlPassword", source.getAuthentication());
        assertTrue(settings.encrypt());
        assertFalse(source.getTrustServerCertificate());
        assertEquals(5, source.getLoginTimeout());
        assertEquals(5000, source.getSocketTimeout());
        assertFalse(settings.toString().contains("password spaces"));
    }

    @Test
    void rejectsMalformedValuesWithoutDisclosingThemInExceptionChains() {
        for (String key : new String[]{"EVENT_DB_PORT", "EVENT_DB_ENCRYPT", "EVENT_DB_TRUST_SERVER_CERTIFICATE", "EVENT_DB_AUTH"}) {
            Map<String, String> input = valid();
            input.put(key, "sensitive-invalid-value");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DatabaseSettings.fromEnvironment(input));
            assertFalse(error.toString().contains("sensitive-invalid-value"));
            assertNull(error.getCause());
        }
        for (String port : new String[]{"0", "65536", "-1"}) {
            Map<String, String> input = valid();
            input.put("EVENT_DB_PORT", port);
            assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.fromEnvironment(input));
        }
    }

    @Test
    void onlySqlAuthenticationIsAcceptedAndUnknownEnvironmentKeysAreRejected() {
        Map<String, String> integrated = new HashMap<>(Map.of("EVENT_DB_HOST", "localhost", "EVENT_DB_NAME", "test_db",
                "EVENT_DB_AUTH", "windows-integrated"));
        var error = assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.fromEnvironment(integrated));
        assertTrue(error.getMessage().contains("Only SQL Server Authentication"));
        assertFalse(error.getMessage().contains("windows-integrated"));
        Map<String, String> unknown = valid();
        unknown.put("EVENT_DB_TRUST_CERTIFICATE", "private-value");
        error = assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.fromEnvironment(unknown));
        assertTrue(error.getMessage().contains("EVENT_DB_TRUST_CERTIFICATE"));
        assertFalse(error.getMessage().contains("private-value"));
    }

    @Test
    void sqlCredentialsAreRequiredWithoutNormalizingTheirDecodedContent() {
        for (String key : new String[]{"EVENT_DB_HOST", "EVENT_DB_NAME", "EVENT_DB_USER", "EVENT_DB_PASSWORD"}) {
            for (boolean missing : new boolean[]{true, false}) {
                Map<String, String> input = valid();
                if (missing) { input.remove(key); } else { input.put(key, ""); }
                var error = assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.fromEnvironment(input));
                assertTrue(error.getMessage().contains(key));
            }
        }
        Map<String, String> input = valid(); input.put("EVENT_DB_PASSWORD", "   ");
        assertEquals("   ", DatabaseSettings.fromEnvironment(input).orElseThrow().value("db.password", ""));
    }
}
