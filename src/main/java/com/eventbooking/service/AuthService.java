package com.eventbooking.service;

import com.eventbooking.dao.UserDao;
import com.eventbooking.exception.InvalidCredentialsException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.UserAccount;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.PasswordHasher;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AuthService {
    // ASCII email addresses avoid case-folding differences between Java and SQL Server.
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    // Valid bcrypt work for unknown accounts; never authenticates an absent account.
    private static final String DUMMY_HASH = "$2a$12$8sLxuN1aT.wm806ikXm4iufWpbJ0kqT38XeqMH54GxaOl0aCLE2wu";
    private final UserDao users;
    private final PasswordHasher passwords;

    public AuthService(UserDao users, PasswordHasher passwords) {
        this.users = java.util.Objects.requireNonNull(users);
        this.passwords = java.util.Objects.requireNonNull(passwords);
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, String> validateCredentials(String email, String password) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (email.length() > 254 || email.indexOf('@') > 64 || !EMAIL.matcher(email).matches()) {
            errors.put("email", "Enter a valid ASCII email address of at most 254 characters.");
        }
        if (!passwords.isValidLength(password)) {
            errors.put("password", "Use 8-72 UTF-8 bytes. Some characters use more than one byte.");
        }
        return errors;
    }

    public long register(String fullName, String email, String password, String confirmation) {
        String name = fullName == null ? "" : fullName.strip();
        String normalizedEmail = normalizeEmail(email);
        Map<String, String> errors = validateCredentials(normalizedEmail, password);
        if (name.isEmpty() || name.length() > 150 || name.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("fullName", "Enter a name of 1-150 characters without control characters.");
        }
        if (password == null || !password.equals(confirmation)) {
            errors.put("confirmation", "The password confirmation does not match.");
        }
        if (!errors.isEmpty()) { throw new ValidationException(errors); }
        String hash = passwords.hash(password);
        try {
            // No SELECT-before-INSERT race; SQL Server's unique email key is authoritative.
            return users.createCustomer(name, normalizedEmail, hash);
        } catch (SQLException error) {
            if (error.getErrorCode() == 2601 || error.getErrorCode() == 2627) {
                throw new ValidationException(Map.of("email", "This email address is already registered."));
            }
            throw new ServiceUnavailableException();
        } catch (UnsatisfiedLinkError unavailableNativeLibrary) {
            throw new ServiceUnavailableException();
        }
    }

    public UserProfile authenticate(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        Map<String, String> errors = validateCredentials(normalizedEmail, password);
        if (!errors.isEmpty()) { throw new ValidationException(errors); }
        try {
            Optional<UserAccount> account = users.findByEmail(normalizedEmail);
            boolean matches = passwords.matches(password, account.map(UserAccount::getPasswordHash).orElse(DUMMY_HASH));
            if (account.isEmpty() || !matches || !account.orElseThrow().isActive()) {
                throw new InvalidCredentialsException();
            }
            return account.orElseThrow().getProfile();
        } catch (SQLException | UnsatisfiedLinkError error) {
            throw new ServiceUnavailableException();
        }
    }

    public Optional<UserProfile> currentUser(long id) {
        try {
            return users.findById(id).filter(UserAccount::isActive).map(UserAccount::getProfile);
        } catch (SQLException | UnsatisfiedLinkError error) {
            throw new ServiceUnavailableException();
        }
    }
}
