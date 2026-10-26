package com.eventbooking.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PasswordHasher {
    public boolean isValidLength(String password) {
        if (password == null || password.length() > 72) {
            return false;
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 8 && bytes <= 72;
    }

    public String hash(String password) {
        if (!isValidLength(password)) {
            throw new IllegalArgumentException("Password must be 8-72 UTF-8 bytes.");
        }
        char[] characters = password.toCharArray();
        try {
            return BCrypt.withDefaults().hashToString(12, characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    public boolean matches(String password, String hash) {
        if (!isValidLength(password) || hash == null || !hash.startsWith("$2a$12$") || hash.length() != 60) {
            return false;
        }
        char[] characters = password.toCharArray();
        try {
            return BCrypt.verifyer().verify(characters, hash).verified;
        } catch (IllegalArgumentException invalidHash) {
            return false;
        } finally {
            Arrays.fill(characters, '\0');
        }
    }
}
