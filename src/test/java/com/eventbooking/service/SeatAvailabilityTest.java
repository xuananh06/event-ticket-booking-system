package com.eventbooking.service;

import com.eventbooking.model.SeatAvailability;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static com.eventbooking.model.SeatAvailability.*;
import static org.junit.jupiter.api.Assertions.*;

class SeatAvailabilityTest {
    @Test void holdExpiresAtEqualityAndNeverRequiresAWrite() {
        var reference = LocalDateTime.of(2030, 1, 1, 0, 0);
        assertEquals(HELD, SeatAvailability.at(true, "HELD", reference.plusNanos(1), reference));
        assertEquals(AVAILABLE, SeatAvailability.at(true, "HELD", reference, reference));
        assertEquals(AVAILABLE, SeatAvailability.at(true, "HELD", reference.minusNanos(1), reference));
        assertEquals(AVAILABLE, SeatAvailability.at(true, "AVAILABLE", null, reference));
    }

    @Test void inactiveSoldOrMalformedInventoryFailsClosed() {
        var reference = LocalDateTime.of(2030, 1, 1, 0, 0);
        assertEquals(UNAVAILABLE, SeatAvailability.at(false, "AVAILABLE", null, reference));
        assertEquals(UNAVAILABLE, SeatAvailability.at(false, "HELD", reference.minusSeconds(1), reference));
        assertEquals(UNAVAILABLE, SeatAvailability.at(true, "SOLD", null, reference));
        assertEquals(UNAVAILABLE, SeatAvailability.at(true, "HELD", null, reference));
        assertEquals(UNAVAILABLE, SeatAvailability.at(true, "unknown", null, reference));
    }
}
