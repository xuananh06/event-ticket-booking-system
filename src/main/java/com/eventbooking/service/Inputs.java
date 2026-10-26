package com.eventbooking.service;

import com.eventbooking.exception.ValidationException;

import java.util.Map;

public final class Inputs {
    private Inputs() {
    }

    public static ValidationException invalid(String field, String message) {
        return new ValidationException(Map.of(field, message));
    }

    public static String text(String value, String field, int limit) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > limit || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field, "Enter 1-" + limit + " characters without control characters.");
        }
        return text;
    }

    public static long id(String value, String field) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
        }
        throw invalid(field, "Enter a valid positive identifier.");
    }

    public static Long optionalId(String value, String field) {
        return value == null || value.isBlank() ? null : id(value, field);
    }

    public static boolean active(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw invalid("active", "Choose active or inactive.");
    }

    public static int positive(String value, String field, int fallback, int maximum) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long number = id(value, field);
        if (number > maximum) {
            throw invalid(field, "The value is too large.");
        }
        return (int) number;
    }
}
