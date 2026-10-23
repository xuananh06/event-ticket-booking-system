package com.eventbooking.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test void boundsAreUtf8BytesAndNeverTruncate() {
        // Dedicated Unicode fixtures prove byte limits without changing user input.
        assertFalse(hasher.isValidLength(null));
        assertFalse(hasher.isValidLength("1234567"));
        assertTrue(hasher.isValidLength("12345678"));
        assertTrue(hasher.isValidLength("đ".repeat(4)));
        assertTrue(hasher.isValidLength("đ".repeat(36)));
        assertFalse(hasher.isValidLength("đ".repeat(37)));
        assertTrue(hasher.isValidLength("🎟".repeat(18)));
        assertFalse(hasher.isValidLength("🎟".repeat(19)));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("x".repeat(73)));
        assertFalse(hasher.matches("x".repeat(73), "$2a$12$" + "x".repeat(53)));
    }

    @Test void samePasswordGetsIndependentSaltsAndMalformedHashesFailSafely() {
        // Explicit Unicode password fixture for UTF-8 hashing interoperability.
        String first = hasher.hash("Unicode đ password");
        String second = hasher.hash("Unicode đ password");
        assertNotEquals(first, second);
        assertTrue(hasher.matches("Unicode đ password", first));
        assertFalse(hasher.matches("another password", first));
        assertFalse(hasher.matches("Unicode đ password", null));
        assertFalse(hasher.matches("Unicode đ password", "$2a$12$" + "!".repeat(53)));
    }
}
