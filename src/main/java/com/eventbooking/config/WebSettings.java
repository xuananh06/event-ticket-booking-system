package com.eventbooking.config;

import java.util.Map;

public record WebSettings(int sessionTimeoutSeconds, boolean secureCookies) {
    public static WebSettings fromEnvironment(Map<String, String> environment) {
        int seconds;
        try {
            seconds = Integer.parseInt(environment.getOrDefault("EVENT_SESSION_TIMEOUT_SECONDS", "1200"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid EVENT_SESSION_TIMEOUT_SECONDS.");
        }
        if (seconds < 1 || seconds > 86400) {
            throw new IllegalArgumentException("Invalid EVENT_SESSION_TIMEOUT_SECONDS.");
        }
        String secure = environment.getOrDefault("EVENT_SESSION_COOKIE_SECURE", "false");
        if (!secure.equalsIgnoreCase("true") && !secure.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Invalid EVENT_SESSION_COOKIE_SECURE.");
        }
        return new WebSettings(seconds, Boolean.parseBoolean(secure));
    }
}
