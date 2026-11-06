package com.eventbooking.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validated SQL Server Authentication settings; values are never diagnostic text. */
public final class DatabaseSettings {
    private static final Map<String, String> ENVIRONMENT_KEYS = Map.of(
            "EVENT_DB_HOST", "db.host", "EVENT_DB_PORT", "db.port", "EVENT_DB_NAME", "db.name",
            "EVENT_DB_USER", "db.user", "EVENT_DB_PASSWORD", "db.password", "EVENT_DB_ENCRYPT", "db.encrypt",
            "EVENT_DB_TRUST_SERVER_CERTIFICATE", "db.trustServerCertificate");
    private static final Set<String> PROPERTY_KEYS = Set.copyOf(ENVIRONMENT_KEYS.values());
    private final Map<String, String> values;

    private DatabaseSettings(Map<String, String> values) { this.values = Map.copyOf(values); }

    public static Optional<DatabaseSettings> fromEnvironment(Map<String, String> environment) {
        Map<String, String> settings = new HashMap<>();
        boolean configured = false;
        for (var entry : environment.entrySet()) {
            if (!entry.getKey().startsWith("EVENT_DB_")) { continue; }
            configured = true;
            if (entry.getKey().equals("EVENT_DB_AUTH")) {
                if (!"sql-password".equals(entry.getValue())) {
                    throw new IllegalArgumentException("Only SQL Server Authentication is supported: EVENT_DB_AUTH.");
                }
            } else {
                String key = ENVIRONMENT_KEYS.get(entry.getKey());
                if (key == null) { throw unsupported(entry.getKey()); }
                settings.put(key, entry.getValue());
            }
        }
        return configured ? Optional.of(validate(settings, true)) : Optional.empty();
    }

    static DatabaseSettings fromProperties(Map<String, String> properties) {
        for (String key : properties.keySet()) {
            if (!PROPERTY_KEYS.contains(key)) { throw unsupported(key); }
        }
        return validate(properties, false);
    }

    private static DatabaseSettings validate(Map<String, String> values, boolean environment) {
        for (String key : new String[]{"db.host", "db.name", "db.user", "db.password"}) {
            String value = values.get(key);
            // Decoded password spaces are meaningful, including an all-space password.
            if (value == null || (key.equals("db.password") ? value.isEmpty() : value.isBlank())) {
                throw invalid(displayKey(key, environment));
            }
        }
        port(values.getOrDefault("db.port", "1433"), displayKey("db.port", environment));
        bool(values, "db.encrypt", true, displayKey("db.encrypt", environment));
        bool(values, "db.trustServerCertificate", false, displayKey("db.trustServerCertificate", environment));
        return new DatabaseSettings(values);
    }

    public String databaseName() { return values.get("db.name"); }
    String value(String key, String fallback) { return values.getOrDefault(key, fallback); }
    int port() { return port(value("db.port", "1433"), "db.port"); }
    boolean encrypt() { return bool(values, "db.encrypt", true, "db.encrypt"); }
    boolean trustServerCertificate() { return bool(values, "db.trustServerCertificate", false, "db.trustServerCertificate"); }

    private static int port(String value, String key) {
        try {
            if (value != null && value.matches("[0-9]{1,5}")) {
                int number = Integer.parseInt(value);
                if (number >= 1 && number <= 65535) { return number; }
            }
        } catch (NumberFormatException ignored) { }
        throw invalid(key);
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback, String diagnosticKey) {
        String value = values.get(key);
        if (value == null) { return fallback; }
        if (value.equalsIgnoreCase("true")) { return true; }
        if (value.equalsIgnoreCase("false")) { return false; }
        throw invalid(diagnosticKey);
    }

    private static String displayKey(String key, boolean environment) {
        if (!environment) { return key; }
        return ENVIRONMENT_KEYS.entrySet().stream().filter(e -> e.getValue().equals(key)).findFirst().orElseThrow().getKey();
    }

    static String safeKey(String key) {
        return key != null && key.matches("(?:db\\.|EVENT_DB_)[A-Za-z0-9_.-]{1,64}") ? key : "<unsupported key>";
    }

    private static IllegalArgumentException unsupported(String key) {
        return new IllegalArgumentException("Unsupported database setting: " + safeKey(key) + ". Use SQL Server Authentication settings only.");
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Missing or invalid database setting: " + safeKey(key) + ".");
    }

    @Override public String toString() { return "DatabaseSettings[redacted]"; }
}
