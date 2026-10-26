package com.eventbooking.service;

import com.eventbooking.exception.*;
import com.eventbooking.dao.*;
import com.eventbooking.model.Page;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagementInputTest {
    @Test
    void boundedIdentifiersPagingAndStrictStates() {
        for (String input : new String[]{"", "-1", "0", "abc", "9223372036854775808"}) {
            assertThrows(ValidationException.class, () -> Inputs.id(input, "id"));
        }
        assertEquals(Long.MAX_VALUE, Inputs.id(String.valueOf(Long.MAX_VALUE), "id"));
        assertNull(Inputs.optionalId("", "id"));
        assertEquals(10, Page.size(null));
        assertEquals(1, Page.number(null));
        assertEquals(49999950L, Page.offset(1000000, 50));
        assertThrows(ValidationException.class, () -> Page.size("51"));
        assertThrows(ValidationException.class, () -> Inputs.active("1"));
        assertFalse(Inputs.active("false"));
        assertTrue(Inputs.active("true"));
    }

    @Test
    void unicodeTextBoundsAndControlsDoNotReachDatabase() {
        // Dedicated Unicode fixture for NVARCHAR-compatible length handling.
        assertEquals("Địa điểm", Inputs.text("  Địa điểm  ", "name", 150));
        assertThrows(ValidationException.class, () -> Inputs.text("x".repeat(101), "name", 100));
        assertThrows(ValidationException.class, () -> Inputs.text("Name\nInjected", "name", 100));
        ReferenceService service = new ReferenceService(new JdbcAccess(Optional.empty()), new ReferenceDao());
        assertThrows(ValidationException.class, () -> service.saveCategory(1, null, " ", true));
        assertThrows(ServiceUnavailableException.class, () -> service.categories(1, null, 1, 10));
    }
}
