package com.eventbooking.integration;

import com.eventbooking.dao.UserDao;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.Role;
import com.eventbooking.security.PasswordHasher;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlServerAuthIT {
    private static final String PASSWORD = " Registration test! ";

    @Test
    void competingCaseVariantUsernamesUseDistinctEmailsAndOneAuthoritativeFieldFailure() throws Exception {
        try (TestDatabase db = new TestDatabase()) {
            String firstEmail = db.email(), secondEmail = db.email(), username = TestDatabase.usernameFor(firstEmail);
            var executor = Executors.newFixedThreadPool(2);
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            try {
                var first = executor.submit(() -> registerUsername(db, firstEmail, username, ready, start));
                var second = executor.submit(() -> registerUsername(db, secondEmail, " " + username.toUpperCase(Locale.ROOT) + " ", ready, start));
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                assertNotEquals(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
                assertEquals(1, db.count(firstEmail) + db.count(secondEmail));
                assertEquals(Role.CUSTOMER, db.auth.authenticate(username, PASSWORD).getRole());
            } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private boolean registerUsername(TestDatabase db, String email, String username, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
        try { db.auth.register("Concurrent username member", username, email, PASSWORD, PASSWORD); return true; }
        catch (ValidationException duplicate) {
            assertEquals(java.util.Set.of("username"), duplicate.getErrors().keySet()); return false;
        }
    }

    @Test
    void registrationPersistsNormalizedCustomerWithCompatibleHashAndUnicode() throws Exception {
        try (TestDatabase db = new TestDatabase()) {
            String email = db.email();
            // Dedicated Unicode fixture also exercises characters requiring JSP output escaping.
            String name = "Nguyễn Minh Anh 🎟 <b>Member</b>";
            long id = db.auth.register(name, " " + TestDatabase.usernameFor(email).toUpperCase(Locale.ROOT) + " ", "  " + email.toUpperCase(Locale.ROOT) + "  ", PASSWORD, PASSWORD);
            var stored = new UserDao(Optional.of(db.source)).findByEmail(email).orElseThrow();
            assertEquals(id, stored.getProfile().getId());
            assertEquals(name, stored.getProfile().getFullName());
            assertEquals(email, stored.getProfile().getEmail());
            assertEquals(Role.CUSTOMER, stored.getProfile().getRole());
            assertTrue(stored.isActive());
            assertTrue(stored.getPasswordHash().startsWith("$2a$12$"));
            assertEquals(60, stored.getPasswordHash().length());
            assertNotEquals(PASSWORD, stored.getPasswordHash());
            assertTrue(new PasswordHasher().matches(PASSWORD, stored.getPasswordHash()));
            assertEquals(id, db.auth.authenticate(email, PASSWORD).getId());
            assertEquals(id, db.auth.authenticate(TestDatabase.usernameFor(email), PASSWORD).getId());
            assertFalse(stored.toString().contains(stored.getPasswordHash()));
        }
    }

    @Test
    void normalizedDuplicateIsControlledAndPreservesOriginalAccount() throws Exception {
        try (TestDatabase db = new TestDatabase()) {
            String email = db.email();
            long id = db.auth.register("Original Member", TestDatabase.usernameFor(email), email, PASSWORD, PASSWORD);
            ValidationException error = assertThrows(ValidationException.class, () -> db.auth.register(
                    "Duplicate Member", TestDatabase.usernameFor("different-" + email), " " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD, PASSWORD));
            assertTrue(error.getErrors().containsKey("email"));
            assertEquals(1, db.count(email));
            assertEquals(id, db.auth.authenticate(email, PASSWORD).getId());
            assertEquals("Original Member", db.auth.currentUser(id).orElseThrow().getFullName());
        }
    }

    @Test
    void competingRegistrationsProduceOneCustomerAndOneValidationFailure() throws Exception {
        try (TestDatabase db = new TestDatabase()) {
            String email = db.email();
            var executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                var action = (java.util.concurrent.Callable<Boolean>) () -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        db.auth.register("Concurrent Member", TestDatabase.usernameFor(java.util.UUID.randomUUID().toString()), email, PASSWORD, PASSWORD);
                        return true;
                    } catch (ValidationException duplicate) {
                        assertTrue(duplicate.getErrors().containsKey("email"));
                        return false;
                    }
                };
                var first = executor.submit(action);
                var second = executor.submit(action);
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
                assertEquals(1, db.count(email));
                assertEquals(Role.CUSTOMER, db.auth.authenticate(email, PASSWORD).getRole());
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
}
