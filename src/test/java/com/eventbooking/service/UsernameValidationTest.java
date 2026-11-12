package com.eventbooking.service;

import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UsernameValidationTest {
    private final PasswordHasher passwords = new PasswordHasher();
    private static final String PASSWORD = " Password unchanged! ";

    @Test
    void normalizationIsLocaleIndependentAndAsciiIsValidatedBeforeCaseFolding() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("info.user_-1", fields("\u2003INFO.User_-1\u2003").username());
            for (String invalid : List.of("\u212aelvin", "caf\u00e9", "\u0130nfo", "user@name", "u ser", "u/ser", "us\u0000er", "x".repeat(31), "ab")) {
                assertEquals(Set.of("username"), assertThrows(ValidationException.class, () -> fields(invalid)).getErrors().keySet());
            }
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void allowedSymbolsAndBothLengthBoundariesAreAccepted() {
        for (String value : List.of("ABC", "._-", "-ab", "Member.Name_0-9", "x".repeat(30))) {
            assertEquals(value.toLowerCase(Locale.ROOT), fields(value).username());
        }
    }

    @Test
    void newAccountsRequireUsernameWhileLegacyProfileEditsMayRemainUnassigned() {
        for (String missing : Arrays.asList(null, "", " \t ")) {
            assertEquals(Set.of("username"), assertThrows(ValidationException.class, () -> fields(missing)).getErrors().keySet());
            assertNull(AccountValidation.profile("Member", missing, "member@example.test").username());
        }
    }

    @Test
    void signInRoutesAtSignsToExistingEmailValidationAndUsesIdentifierFieldErrors() {
        assertEquals("member@example.test", AccountValidation.credentials(" MEMBER@EXAMPLE.TEST ", PASSWORD, passwords));
        assertEquals("member.name", AccountValidation.credentials(" Member.Name ", PASSWORD, passwords));
        for (String value : List.of("a@@example.test", "bad@local", "ab", "name with spaces", "x".repeat(31), "<script>")) {
            assertEquals(Set.of("identifier"), assertThrows(ValidationException.class,
                    () -> AccountValidation.credentials(value, PASSWORD, passwords)).getErrors().keySet());
        }
    }

    @Test
    void uniquenessErrorsUseOnlyKnownObjectNamesAndNeverExposePrivateDetails() {
        for (int code : List.of(2601, 2627)) {
            for (String field : List.of("username", "email")) {
                String object = field.equals("username") ? "UX_users_username" : "UQ_users_email";
                RuntimeException error = AccountConflicts.failure(new SQLException("Object '" + object + "'; private row and server details", "23000", code));
                assertInstanceOf(ValidationException.class, error);
                assertEquals(Set.of(field), ((ValidationException) error).getErrors().keySet());
                assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
                assertFalse(error.toString().contains("private"));
            }
        }
        SQLException wrapped = new SQLException("Private wrapper");
        wrapped.setNextException(new SQLException("Index 'UX_users_username'", "23000", 2601));
        assertInstanceOf(ValidationException.class, AccountConflicts.failure(wrapped));
        for (SQLException unknown : List.of(new SQLException("private values", "23000", 2627), new SQLException("private server", "08001", 0))) {
            RuntimeException error = AccountConflicts.failure(unknown);
            assertInstanceOf(ServiceUnavailableException.class, error); assertNull(error.getCause());
            assertFalse(error.toString().contains("private"));
        }
    }

    private AccountValidation.Fields fields(String username) {
        return AccountValidation.registration("Member", username, "member@example.test", PASSWORD, PASSWORD, passwords);
    }
}
