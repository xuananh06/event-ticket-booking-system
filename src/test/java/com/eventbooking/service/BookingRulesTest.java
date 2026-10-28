package com.eventbooking.service;

import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class BookingRulesTest {
    @Test void selectionsAreBoundedDistinctPositiveAndCanonical() {
        assertEquals(List.of(1L, 3L), InventoryInputs.selection(new String[]{"3", "01"}));
        for (String[] invalid : new String[][]{null, {}, {""}, {"0"}, {"-1"}, {"1.0"}, {"bad"}, {"9223372036854775808"}, {"1", "01"}}) {
            assertThrows(ValidationException.class, () -> InventoryInputs.selection(invalid));
        }
        assertEquals(50, InventoryInputs.selection(LongStream.rangeClosed(1, 50).mapToObj(String::valueOf).toArray(String[]::new)).size());
        assertThrows(ValidationException.class, () -> InventoryInputs.selection(LongStream.rangeClosed(1, 51).mapToObj(String::valueOf).toArray(String[]::new)));
    }

    @Test void totalsAreExactAndRejectAggregateOverflowWithoutRounding() {
        assertEquals(new BigDecimal("0.00"), BookingRules.total(List.of(BigDecimal.ZERO)));
        assertEquals(new BigDecimal("11.30"), BookingRules.total(List.of(new BigDecimal("1.10"), new BigDecimal("10.20"))));
        assertEquals(InventoryInputs.MAX_PRICE, BookingRules.total(List.of(InventoryInputs.MAX_PRICE, BigDecimal.ZERO)));
        assertThrows(DomainException.class, () -> BookingRules.total(List.of(InventoryInputs.MAX_PRICE, new BigDecimal("0.01"))));
        assertThrows(DomainException.class, () -> BookingRules.total(List.of(new BigDecimal("1.001"))));
        assertThrows(DomainException.class, () -> BookingRules.total(List.of(new BigDecimal("-1"))));
    }

    @Test void snapshotsPreserveUnicodeAndFitTheSchemaWithoutHtmlInterpretation() {
        // Dedicated Unicode/XSS fixture; views must escape this plain snapshot.
        assertEquals("Row H\u00e0<& / 12", BookingRules.seatLabel("H\u00e0<&", 12));
        assertTrue(BookingRules.seatLabel("X".repeat(10), Integer.MAX_VALUE).length() <= 30);
        assertThrows(DomainException.class, () -> BookingRules.seatLabel("\n", 1));
        assertThrows(DomainException.class, () -> BookingRules.seatLabel("A", 0));
    }

    @Test void pendingExpiresAtEqualityAndOtherStatesRemainUnchanged() {
        LocalDateTime deadline = LocalDateTime.of(2030, 1, 1, 0, 0);
        assertEquals("PENDING", BookingRules.effectiveStatus("PENDING", deadline, deadline.minusNanos(1)));
        assertEquals("EXPIRED", BookingRules.effectiveStatus("PENDING", deadline, deadline));
        assertEquals("EXPIRED", BookingRules.effectiveStatus("PENDING", deadline, deadline.plusNanos(1)));
        for (String state : List.of("CONFIRMED", "CANCELLED", "EXPIRED")) {
            assertEquals(state, BookingRules.effectiveStatus(state, deadline, deadline.plusYears(1)));
        }
    }
}
