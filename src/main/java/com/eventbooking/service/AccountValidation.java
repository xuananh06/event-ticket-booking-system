package com.eventbooking.service;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.security.PasswordHasher;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared account input contract for public registration and administrative creation/editing.
 */
public final class AccountValidation {
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,30}");

    private AccountValidation() {
    }

    public record Fields(String name, String username, String email) {
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> emailErrors(String email) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (email.length() > 254 || email.indexOf('@') > 64 || !EMAIL.matcher(email).matches()) {
            errors.put("email", "Enter a valid ASCII email address of at most 254 characters.");
        }
        return errors;
    }

    public static String credentials(String identifier, String password, PasswordHasher passwords) {
        String input = identifier == null ? "" : identifier.strip();
        Map<String, String> errors = new LinkedHashMap<>();
        String normalized;
        if (input.contains("@")) {
            normalized = normalize(input);
            String error = emailErrors(normalized).get("email");
            if (error != null) { errors.put("identifier", error); }
        } else {
            normalized = username(errors, input, true, "identifier");
        }
        passwordErrors(errors, password, passwords);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return normalized;
    }

    private static void passwordErrors(Map<String, String> errors, String password, PasswordHasher passwords) {
        if (!passwords.isValidLength(password)) {
            errors.put("password", "Use 8-72 UTF-8 bytes. Some characters use more than one byte.");
        }
    }

    private static String username(Map<String, String> errors, String value, boolean required, String field) {
        String input = value == null ? "" : value.strip();
        if (input.isEmpty() && !required) { return null; }
        // Validate ASCII before lowercasing; Unicode case folding must not admit other characters.
        if (!USERNAME.matcher(input).matches()) {
            errors.put(field, "Use 3-30 ASCII letters, digits, periods, underscores or hyphens. Do not use @.");
        }
        return input.toLowerCase(Locale.ROOT);
    }

    public static Fields profile(String name, String username, String email) {
        return validate(name, username, email, null, null, null);
    }

    public static Fields registration(String name, String username, String email, String password, String confirmation, PasswordHasher passwords) {
        return validate(name, username, email, password, confirmation, passwords);
    }

    private static Fields validate(String name, String username, String email, String password, String confirmation, PasswordHasher passwords) {
        String normalized = normalize(email), display = name == null ? "" : name.strip();
        Map<String, String> errors = emailErrors(normalized);
        String normalizedUsername = username(errors, username, passwords != null, "username");
        if (display.isEmpty() || display.length() > 150 || display.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("fullName", "Enter a name of 1-150 characters without control characters.");
        }
        if (passwords != null) {
            passwordErrors(errors, password, passwords);
            if (password == null || !password.equals(confirmation)) {
                errors.put("confirmation", "The password confirmation does not match.");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Fields(display, normalizedUsername, normalized);
    }
}
