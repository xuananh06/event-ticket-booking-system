package com.eventbooking.service;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.model.InventorySeat;
import com.eventbooking.model.SeatLayout;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryInputsTest {
    @Test void moneyRequiresAnExplicitExactSupportedDecimal() {
        assertEquals(new BigDecimal("0.00"), InventoryInputs.price("0"));
        assertEquals(new BigDecimal("1234.50"), InventoryInputs.price(" 1234.5 "));
        assertEquals(InventoryInputs.MAX_PRICE, InventoryInputs.price("9999999999999999.99"));
        for (String value : new String[]{null, "", " ", "-0", "-1", "+1", ".5", "1.", "1.001", "1.000", "1e2", "NaN", "Infinity", "1,000", "10000000000000000", "9".repeat(65)}) {
            assertThrows(ValidationException.class, () -> InventoryInputs.price(value));
        }
    }

    @Test void selectionsAreNonemptyDistinctBoundedAndSorted() {
        assertEquals(List.of(1L, 2L, Long.MAX_VALUE), InventoryInputs.selection(new String[]{"2", String.valueOf(Long.MAX_VALUE), "1"}));
        String[] maximum = java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(String::valueOf).toArray(String[]::new);
        assertEquals(50, InventoryInputs.selection(maximum).size());
        for (String[] values : new String[][]{null, {}, {"1", "1"}, {"1", "01"}, {"0"}, {"-1"}, {"x"}, {""}, {"9223372036854775808"}, new String[51]}) {
            assertThrows(ValidationException.class, () -> InventoryInputs.selection(values));
        }
    }

    @Test void validInventoryUseCasesFailSafelyWithoutDatabaseConfiguration() {
        JdbcAccess database = new JdbcAccess(Optional.empty());
        InventoryService inventory = new InventoryService(database, new EventDao(), new InventoryDao());
        assertThrows(ServiceUnavailableException.class, () -> inventory.generate(1, 1, "0"));
        assertThrows(ServiceUnavailableException.class, () -> inventory.price(1, 1, new String[]{"1"}, "0"));
        assertThrows(ServiceUnavailableException.class, () -> inventory.publish(1, 1));
        assertThrows(ServiceUnavailableException.class, () -> inventory.view(1, 1, 1, 10));
        assertThrows(ServiceUnavailableException.class, () -> new PublicInventoryService(database, new EventDao(), new InventoryDao()).view(1, 1, 10));
    }

    @Test void layoutOrdersRealLabelsNumbersAndIdentityWithoutSparseArrays() {
        // Dedicated Unicode and HTML fixture labels, treated only as user data.
        var seats = List.of(seat(4, "Ω <b>", Integer.MAX_VALUE), seat(3, "A", 50), seat(2, "A", 2), seat(1, "A", 2));
        var rows = SeatLayout.rows(seats);
        assertEquals(2, rows.size());
        assertEquals(List.of(1L, 2L, 3L), rows.get(0).seats().stream().map(InventorySeat::id).toList());
        assertEquals("Ω <b>", rows.get(1).label());
        assertEquals(1, rows.get(1).seats().size());
        assertTrue(SeatLayout.rows(List.of()).isEmpty());
    }

    private InventorySeat seat(long id, String row, int number) {
        return new InventorySeat(id, id, row, number, new BigDecimal("1.00"), "AVAILABLE");
    }
}
