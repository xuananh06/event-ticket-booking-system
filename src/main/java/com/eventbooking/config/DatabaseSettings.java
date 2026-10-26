package com.eventbooking.config;

import java.util.Map;
import java.util.Optional;

/**
 * Environment values are never included in diagnostic messages or toString().
 */
public final class DatabaseSettings {
    private final Map<String, String> values;

    private DatabaseSettings(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Optional<DatabaseSettings> fromEnvironment(Map<String, String> environment) {
        boolean configured = environment.keySet().stream().anyMatch(key -> key.startsWith("EVENT_DB_"));
        if (!configured) {
            return Optional.empty();
        }
        require(environment, "EVENT_DB_HOST");
        require(environment, "EVENT_DB_NAME");
        String auth = environment.getOrDefault("EVENT_DB_AUTH", "sql-password");
        if (!auth.equals("sql-password") && !auth.equals("windows-integrated")) {
            throw invalid("EVENT_DB_AUTH");
        }
        if (auth.equals("sql-password")) {
            require(environment, "EVENT_DB_USER");
            require(environment, "EVENT_DB_PASSWORD");
        } else if (environment.containsKey("EVENT_DB_USER") || environment.containsKey("EVENT_DB_PASSWORD")) {
            throw new IllegalArgumentException("Do not mix database authentication modes.");
        }
        parsePort(environment.getOrDefault("EVENT_DB_PORT", "1433"));
        parseBoolean(environment, "EVENT_DB_ENCRYPT", true);
        parseBoolean(environment, "EVENT_DB_TRUST_SERVER_CERTIFICATE", false);
        // Retain only recognized keys, never the entire process environment.
        Map<String, String> settings = new java.util.HashMap<>();
        for (String key : new String[]{"EVENT_DB_HOST", "EVENT_DB_NAME", "EVENT_DB_AUTH", "EVENT_DB_PORT",
                "EVENT_DB_USER", "EVENT_DB_PASSWORD", "EVENT_DB_ENCRYPT", "EVENT_DB_TRUST_SERVER_CERTIFICATE"}) {
            if (environment.containsKey(key)) {
                settings.put(key, environment.get(key));
            }
        }
        return Optional.of(new DatabaseSettings(settings));
    }

    String value(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    int port() {
        return parsePort(value("EVENT_DB_PORT", "1433"));
    }

    boolean encrypt() {
        return parseBoolean(values, "EVENT_DB_ENCRYPT", true);
    }

    boolean trustServerCertificate() {
        return parseBoolean(values, "EVENT_DB_TRUST_SERVER_CERTIFICATE", false);
    }

    private static void require(Map<String, String> environment, String key) {
        if (environment.get(key) == null || environment.get(key).isBlank()) {
            throw invalid(key);
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Do not attach an exception whose message contains external configuration.
        }
        throw invalid("EVENT_DB_PORT");
    }

    private static boolean parseBoolean(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw invalid(key);
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Missing or invalid database setting: " + key);
    }

    @Override
    public String toString() {
        return "DatabaseSettings[redacted]";
    }
}
