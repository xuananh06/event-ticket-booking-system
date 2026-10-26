package com.eventbooking.service;

import com.eventbooking.dao.UserDao;
import com.eventbooking.exception.InvalidCredentialsException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.UserAccount;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.PasswordHasher;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

public final class AuthService {
    // Valid bcrypt work for unknown accounts; never authenticates an absent account.
    private static final String DUMMY_HASH = "$2a$12$8sLxuN1aT.wm806ikXm4iufWpbJ0kqT38XeqMH54GxaOl0aCLE2wu";
    private final UserDao users;
    private final PasswordHasher passwords;

    public AuthService(UserDao users, PasswordHasher passwords) {
        this.users = java.util.Objects.requireNonNull(users);
        this.passwords = java.util.Objects.requireNonNull(passwords);
    }

    public static String normalizeEmail(String email) {
        return AccountValidation.normalize(email);
    }

    public long register(String fullName, String email, String password, String confirmation) {
        AccountValidation.Fields fields = AccountValidation.registration(fullName, email, password, confirmation, passwords);
        String hash = passwords.hash(password);
        try {
            // No SELECT-before-INSERT race; SQL Server's unique email key is authoritative.
            return users.createCustomer(fields.name(), fields.email(), hash);
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
        String normalizedEmail = AccountValidation.credentials(email, password, passwords);
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
