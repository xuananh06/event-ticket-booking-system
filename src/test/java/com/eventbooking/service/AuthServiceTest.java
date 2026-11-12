package com.eventbooking.service;

import com.eventbooking.dao.UserDao;
import com.eventbooking.exception.InvalidCredentialsException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.Role;
import com.eventbooking.model.UserAccount;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.PasswordHasher;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    private static final String PASSWORD = "  Demo pass!  ";
    private static String hash;

    @BeforeAll
    static void hashFixture() {
        hash = new PasswordHasher().hash(PASSWORD);
    }

    private static class Users extends UserDao {
        UserAccount account;
        String email;
        String username;
        String name;
        String storedHash;
        SQLException failure;
        int writes;

        Users() {
            super(Optional.empty());
        }

        @Override
        public long createCustomer(String name, String username, String email, String hash) throws SQLException {
            if (failure != null) {
                throw failure;
            }
            this.name = name;
            this.username = username;
            this.email = email;
            storedHash = hash;
            writes++;
            return 7;
        }

        @Override
        public Optional<UserAccount> findByEmail(String email) throws SQLException {
            if (failure != null) {
                throw failure;
            }
            this.email = email;
            return Optional.ofNullable(account);
        }

        @Override
        public Optional<UserAccount> findByUsername(String username) throws SQLException {
            if (failure != null) { throw failure; }
            this.username = username;
            return Optional.ofNullable(account);
        }

        @Override
        public Optional<UserAccount> findById(long id) throws SQLException {
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(account);
        }
    }

    private UserAccount account(boolean active) {
        return new UserAccount(new UserProfile(7, "demo@example.test", "Demo Customer", Role.CUSTOMER), active, hash);
    }

    @Test
    void normalizationIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("info@example.test", AuthService.normalizeEmail("  INFO@EXAMPLE.TEST  "));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void registrationNormalizesSafeFieldsAndHashesUntrimmedPassword() {
        Users users = new Users();
        assertEquals(7, new AuthService(users, new PasswordHasher()).register("  Demo Customer  ", " Demo_User-1.2 ", " DEMO@EXAMPLE.TEST ", PASSWORD, PASSWORD));
        assertEquals("Demo Customer", users.name);
        assertEquals("demo@example.test", users.email);
        assertEquals("demo_user-1.2", users.username);
        assertTrue(new PasswordHasher().matches(PASSWORD, users.storedHash));
        assertFalse(new PasswordHasher().matches(PASSWORD.trim(), users.storedHash));
        assertTrue(users.storedHash.startsWith("$2a$12$"));
        assertFalse(account(true).toString().contains(hash));
    }

    @Test
    void invalidFieldsAreCollectedBeforeAnyWrite() {
        Users users = new Users();
        AuthService service = new AuthService(users, new PasswordHasher());
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.register(" ", "", "a..b@example.test", "short", "different"));
        assertEquals(java.util.Set.of("fullName", "username", "email", "password", "confirmation"), error.getErrors().keySet());
        assertEquals(0, users.writes);
        assertThrows(ValidationException.class, () -> service.register("x".repeat(151), "member", "a@example.test", PASSWORD, PASSWORD));
        assertThrows(ValidationException.class, () -> service.register("Name\nInjected", "member", "a@example.test", PASSWORD, PASSWORD));
        // Includes an explicit Unicode email fixture to verify the documented ASCII email rule.
        for (String email : new String[]{"a@localhost", "a@@example.test", "đ@example.test", "a@-bad.test", "a".repeat(65) + "@example.test", "a".repeat(255) + "@example.test"}) {
            assertThrows(ValidationException.class, () -> service.register("Name", "member", email, PASSWORD, PASSWORD));
        }
    }

    @Test
    void duplicateEmailIsAControlledFieldErrorButOtherSqlFailuresAreUnavailable() {
        Users users = new Users();
        AuthService service = new AuthService(users, new PasswordHasher());
        users.failure = new SQLException("Constraint 'UQ_users_email'; private server details", "23000", 2627);
        ValidationException duplicate = assertThrows(ValidationException.class,
                () -> service.register("Name", "member", "a@example.test", PASSWORD, PASSWORD));
        assertEquals(java.util.Set.of("email"), duplicate.getErrors().keySet());
        users.failure = new SQLException("secret connection information", "08001", 0);
        ServiceUnavailableException unavailable = assertThrows(ServiceUnavailableException.class,
                () -> service.authenticate("a@example.test", PASSWORD));
        assertNull(unavailable.getCause());
        assertFalse(unavailable.toString().contains("secret"));
    }

    @Test
    void correctLoginReturnsOnlyTheSafeProfile() {
        Users users = new Users();
        users.account = account(true);
        UserProfile profile = new AuthService(users, new PasswordHasher()).authenticate(" DEMO@EXAMPLE.TEST ", PASSWORD);
        assertEquals(7, profile.getId());
        assertEquals(Role.CUSTOMER, profile.getRole());
        assertFalse(profile.toString().contains(hash));
    }

    @Test
    void bothIdentifiersUseTheSamePasswordFlowForEveryRole() {
        Users users = new Users();
        AuthService service = new AuthService(users, new PasswordHasher());
        for (Role role : Role.values()) {
            users.account = new UserAccount(new UserProfile(7, "demo@example.test", "Member", role), true, hash);
            assertEquals(role, service.authenticate(" Demo.User_1-2 ", PASSWORD).getRole());
            assertEquals("demo.user_1-2", users.username);
            assertEquals(role, service.authenticate(" DEMO@EXAMPLE.TEST ", PASSWORD).getRole());
            assertEquals("demo@example.test", users.email);
        }
        users.account = null;
        assertThrows(InvalidCredentialsException.class, () -> service.authenticate("unknown-user", "DemoOnly!ChangeMe"));
        assertThrows(InvalidCredentialsException.class, () -> service.authenticate("unknown@example.test", "DemoOnly!ChangeMe"));
    }

    @Test
    void usernameFailuresRemainGenericAndMalformedIdentifiersNeverReachTheDao() {
        Users users = new Users();
        AuthService service = new AuthService(users, new PasswordHasher());
        for (String invalid : new String[]{"ab", "a@@example.test", "\u212aelvin", "<script>", "has space"}) {
            assertEquals(java.util.Set.of("identifier"), assertThrows(ValidationException.class,
                    () -> service.authenticate(invalid, PASSWORD)).getErrors().keySet());
            assertNull(users.username); assertNull(users.email);
        }
        String absent = assertThrows(InvalidCredentialsException.class, () -> service.authenticate("unknown-user", PASSWORD)).getMessage();
        users.account = account(true);
        assertEquals(absent, assertThrows(InvalidCredentialsException.class, () -> service.authenticate("demo-user", "incorrect pass")).getMessage());
        users.account = account(false);
        assertEquals(absent, assertThrows(InvalidCredentialsException.class, () -> service.authenticate("demo-user", PASSWORD)).getMessage());
        assertEquals(absent, assertThrows(InvalidCredentialsException.class, () -> service.authenticate("demo@example.test", PASSWORD)).getMessage());
    }

    @Test
    void unknownWrongPasswordAndInactiveAccountsShareTheCredentialFailure() {
        Users users = new Users();
        AuthService service = new AuthService(users, new PasswordHasher());
        String unknown = assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate("unknown@example.test", PASSWORD)).getMessage();
        users.account = account(true);
        assertEquals(unknown, assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate("demo@example.test", "incorrect pass")).getMessage());
        users.account = account(false);
        assertEquals(unknown, assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate("demo@example.test", PASSWORD)).getMessage());
        assertTrue(service.currentUser(7).isEmpty());
    }

    @Test
    void currentAccountIsReadAgainRatherThanUsingAnOldRole() {
        Users users = new Users();
        users.account = account(true);
        AuthService service = new AuthService(users, new PasswordHasher());
        assertEquals(Role.CUSTOMER, service.currentUser(7).orElseThrow().getRole());
        users.account = new UserAccount(new UserProfile(7, "demo@example.test", "Name", Role.ADMIN), true, null);
        assertEquals(Role.ADMIN, service.currentUser(7).orElseThrow().getRole());
        users.account = null;
        assertTrue(service.currentUser(7).isEmpty());
    }

    @Test
    void noDatabaseIsAServiceFailureForAllDatabaseDependentOperations() {
        AuthService service = new AuthService(new UserDao(Optional.empty()), new PasswordHasher());
        assertThrows(ServiceUnavailableException.class, () -> service.register("Name", "member", "a@example.test", PASSWORD, PASSWORD));
        assertThrows(ServiceUnavailableException.class, () -> service.authenticate("a@example.test", PASSWORD));
        assertThrows(ServiceUnavailableException.class, () -> service.currentUser(1));
    }
}
