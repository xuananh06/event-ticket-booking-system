package com.eventbooking.service;

import com.eventbooking.dao.UserDao;
import com.eventbooking.exception.InvalidCredentialsException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.model.UserAccount;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.PasswordHasher;

import java.sql.SQLException;
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

    public long register(String fullName, String username, String email, String password, String confirmation) {
        AccountValidation.Fields fields = AccountValidation.registration(fullName, username, email, password, confirmation, passwords);
        String hash = passwords.hash(password);
        try {
            // SQL Server's unique username index and email key are authoritative, including races.
            return users.createCustomer(fields.name(), fields.username(), fields.email(), hash);
        } catch (SQLException error) {
            throw AccountConflicts.failure(error);
        } catch (UnsatisfiedLinkError unavailableNativeLibrary) {
            throw new ServiceUnavailableException();
        }
    }

    public UserProfile authenticate(String identifier, String password) {
        String normalized = AccountValidation.credentials(identifier, password, passwords);
        try {
            Optional<UserAccount> account = normalized.contains("@") ? users.findByEmail(normalized) : users.findByUsername(normalized);
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
