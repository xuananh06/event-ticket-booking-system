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
    void managementCreatesNormalizesEditsAndPreservesHashes() throws Exception {
        try (P3Fixture f = new P3Fixture()) {
            AdminUserService service = new AdminUserService(f.access, new AdminUserDao(), new PasswordHasher());
            String email = f.db.email();
            long id = service.create(f.admin, "Created Member", " " + email.toUpperCase(Locale.ROOT) + " ", P3Fixture.PASSWORD, P3Fixture.PASSWORD, Role.ORGANIZER, true);
            var account = new UserDao(Optional.of(f.db.source)).findByEmail(email).orElseThrow();
            String original = account.getPasswordHash();
            assertTrue(new PasswordHasher().matches(P3Fixture.PASSWORD, original));
            assertEquals(Role.ORGANIZER, service.find(f.admin, id).role());
            service.save(f.admin, id, "Edited Member", email, Role.CUSTOMER, false);
            assertTrue(original.equals(new UserDao(Optional.of(f.db.source)).findByEmail(email).orElseThrow().getPasswordHash()), "Profile edits must preserve the encoded hash.");
            assertFalse(service.find(f.admin, id).active());
            assertEquals(1, service.list(f.admin, email, null, 1, 10).total());
            assertEquals(409, assertThrows(DomainException.class, () -> service.create(f.admin, "Duplicate", email, P3Fixture.PASSWORD, P3Fixture.PASSWORD, Role.CUSTOMER, true)).getStatus());
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
            assertEquals(409, assertThrows(DomainException.class, () -> service.save(f.admin, organizer, "Member", f.email(organizer), Role.CUSTOMER, true)).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> service.save(f.admin, customer, "Member", f.email(customer), Role.ORGANIZER, true)).getStatus());
            assertEquals(409, assertThrows(DomainException.class, () -> service.delete(f.admin, customer)).getStatus());
            assertEquals(403, assertThrows(DomainException.class, () -> service.status(customer, organizer, false)).getStatus());
            service.status(f.admin, customer, false);
            assertTrue(f.db.auth.currentUser(customer).isEmpty());
        }
    }
}
