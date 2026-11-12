package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.exception.*;
import com.eventbooking.model.*;
import com.eventbooking.security.PasswordHasher;
import com.eventbooking.service.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import static com.eventbooking.dao.JdbcAccess.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminAccountsIT {
    @Test
    void legacyAssignmentRenameClearRejectionAndStatusPreserveCredentials() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            var service = new AdminUserService(f.access, new AdminUserDao(), new PasswordHasher());
            String email = f.db.email();
            long id = f.db.legacyUser("Legacy Member", email, P3Fixture.PASSWORD);
            String originalHash = new UserDao(Optional.of(f.db.source)).findByEmail(email).orElseThrow().getPasswordHash();
            assertNull(service.find(f.admin, id).username());
            service.save(f.admin, id, "Unrelated profile edit", " ", email, Role.CUSTOMER, true);
            assertNull(service.find(f.admin, id).username());
            assertEquals(id, f.db.auth.authenticate(email, P3Fixture.PASSWORD).getId());
            String username = TestDatabase.usernameFor(email);
            service.save(f.admin, id, "Assigned Member", " " + username.toUpperCase(Locale.ROOT) + " ", email, Role.CUSTOMER, true);
            assertEquals(username, service.find(f.admin, id).username());
            assertEquals(id, f.db.auth.authenticate(username, P3Fixture.PASSWORD).getId());
            for (String cleared : Arrays.asList(null, "", " \t ")) {
                assertEquals(Set.of("username"), assertThrows(ValidationException.class,
                        () -> service.save(f.admin, id, "Must not change", cleared, email, Role.CUSTOMER, true)).getErrors().keySet());
                assertEquals("Assigned Member", service.find(f.admin, id).fullName());
                assertEquals(username, service.find(f.admin, id).username());
            }
            service.status(f.admin, id, false);
            assertEquals(username, service.find(f.admin, id).username());
            assertThrows(InvalidCredentialsException.class, () -> f.db.auth.authenticate(username, P3Fixture.PASSWORD));
            assertThrows(InvalidCredentialsException.class, () -> f.db.auth.authenticate(email, P3Fixture.PASSWORD));
            service.status(f.admin, id, true);
            String renamed = TestDatabase.usernameFor("renamed-" + email);
            service.save(f.admin, id, "Renamed Member", renamed, email, Role.CUSTOMER, true);
            assertEquals(id, f.db.auth.authenticate(renamed, P3Fixture.PASSWORD).getId());
            assertThrows(InvalidCredentialsException.class, () -> f.db.auth.authenticate(username, P3Fixture.PASSWORD));
            assertTrue(originalHash.equals(new UserDao(Optional.of(f.db.source)).findByEmail(email).orElseThrow().getPasswordHash()), "Username changes must preserve the encoded password.");
            assertEquals(1, service.list(f.admin, renamed, null, 1, 10).total());
            String anotherEmail = f.db.email();
            assertEquals(Set.of("username"), assertThrows(ValidationException.class,
                    () -> service.create(f.admin, "Duplicate username", renamed.toUpperCase(Locale.ROOT), anotherEmail, P3Fixture.PASSWORD, P3Fixture.PASSWORD, Role.CUSTOMER, true)).getErrors().keySet());
            assertEquals(0, f.db.count(anotherEmail));
        }
    }

    @Test
    void managementCreatesNormalizesEditsAndPreservesHashes() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            AdminUserService service = new AdminUserService(f.access, new AdminUserDao(), new PasswordHasher());
            String email = f.db.email();
            long id = service.create(f.admin, "Created Member", TestDatabase.usernameFor(email), " " + email.toUpperCase(Locale.ROOT) + " ", P3Fixture.PASSWORD, P3Fixture.PASSWORD, Role.ORGANIZER, true);
            var account = new UserDao(Optional.of(f.db.source)).findByEmail(email).orElseThrow();
            String original = account.getPasswordHash();
            assertTrue(new PasswordHasher().matches(P3Fixture.PASSWORD, original));
            assertEquals(Role.ORGANIZER, service.find(f.admin, id).role());
            service.save(f.admin, id, "Edited Member", TestDatabase.usernameFor(email), email, Role.CUSTOMER, false);
            assertTrue(original.equals(new UserDao(Optional.of(f.db.source)).findByEmail(email).orElseThrow().getPasswordHash()), "Profile edits must preserve the encoded hash.");
            assertFalse(service.find(f.admin, id).active());
            assertEquals(1, service.list(f.admin, email, null, 1, 10).total());
            assertEquals(Set.of("email"), assertThrows(ValidationException.class, () -> service.create(f.admin, "Duplicate", TestDatabase.usernameFor("different-" + email), email, P3Fixture.PASSWORD, P3Fixture.PASSWORD, Role.CUSTOMER, true)).getErrors().keySet());
            service.delete(f.admin, id);
            assertEquals(0, f.db.count(email));
            assertEquals(409, assertThrows(DomainException.class, () -> service.status(f.admin, f.admin, false)).getStatus());
        }
    }

    @Test
    void ownershipHistoryAndServiceActorsAreRevalidated() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            AdminUserService service = new AdminUserService(f.access, new AdminUserDao(), new PasswordHasher());
            long organizer = f.user("ORGANIZER"), customer = f.user("CUSTOMER"), category = f.category(), venue = f.venue();
            long event = f.event(organizer, category, venue, f.prefix, "DRAFT", 20);
            long booking = f.access.write(c -> scalar(c, "DECLARE @now datetime2(3)=SYSUTCDATETIME(); INSERT dbo.bookings(booking_code,customer_id,event_id,total_amount,created_at,expires_at) OUTPUT INSERTED.id VALUES (?,?,?,0,@now,DATEADD(MINUTE,5,@now))", "P3-" + UUID.randomUUID(), customer, event));
            f.bookings.add(booking);
            assertEquals(409, assertThrows(DomainException.class, () -> service.save(f.admin, organizer, "Member", TestDatabase.usernameFor(f.email(organizer)), f.email(organizer), Role.CUSTOMER, true)).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> service.save(f.admin, customer, "Member", TestDatabase.usernameFor(f.email(customer)), f.email(customer), Role.ORGANIZER, true)).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> service.delete(f.admin, customer)).getStatus());
            assertEquals(403, assertThrows(DomainException.class, () -> service.status(customer, organizer, false)).getStatus());
            service.status(f.admin, customer, false);
            assertTrue(f.db.auth.currentUser(customer).isEmpty());
        }
    }
}
