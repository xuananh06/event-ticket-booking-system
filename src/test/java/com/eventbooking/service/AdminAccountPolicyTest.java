package com.eventbooking.service;

import com.eventbooking.exception.*;
import com.eventbooking.model.Role;
import com.eventbooking.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminAccountPolicyTest {
    @Test void selfLastAdminAndHistoryGuards() {
        assertThrows(DomainException.class,()->AdminAccountPolicy.change(1,1,Role.ADMIN,true,Role.CUSTOMER,true,false,2,0,0));
        assertThrows(DomainException.class,()->AdminAccountPolicy.change(1,2,Role.ADMIN,true,Role.ADMIN,false,false,1,0,0));
        assertThrows(DomainException.class,()->AdminAccountPolicy.change(1,2,Role.ORGANIZER,true,Role.CUSTOMER,true,false,2,1,0));
        assertThrows(DomainException.class,()->AdminAccountPolicy.change(1,2,Role.CUSTOMER,true,Role.ORGANIZER,true,false,2,0,1));
        assertDoesNotThrow(()->AdminAccountPolicy.change(1,2,Role.ORGANIZER,true,Role.ORGANIZER,false,false,2,1,0));
    }
    @Test void sharedValidationAndRoleAllowlists() {
        var hasher=new PasswordHasher();
        assertEquals("member@example.test",AccountValidation.registration(" Member "," MEMBER@EXAMPLE.TEST "," Password! "," Password! ",hasher).email());
        assertThrows(ValidationException.class,()->AccountValidation.registration("Member","a@example.test","short","different",hasher));
        assertThrows(ValidationException.class,()->AdminUserService.role("admin"));
        assertThrows(ValidationException.class,()->AdminUserService.role(null));
        assertEquals(Role.ADMIN,AdminUserService.role("ADMIN"));
    }
}
