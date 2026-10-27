package com.eventbooking.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeSet;

/**
 * Exact money and bounded inventory selections; never rounds accepted input.
 */
public final class InventoryInputs {
    public static final int MAX_SELECTION = 50;
    public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999999.99");

    private InventoryInputs() {
    }

    public static BigDecimal price(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() > 64 || !text.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw Inputs.invalid("price", "Enter a nonnegative decimal price with at most two fractional digits.");
        }
        BigDecimal price = new BigDecimal(text);
        if (price.compareTo(MAX_PRICE) > 0) {
            throw Inputs.invalid("price", "The price exceeds the supported VND amount.");
        }
        return price.setScale(2);
    }

    public static List<Long> selection(String[] values) {
        if (values == null || values.length == 0 || values.length > MAX_SELECTION) {
            throw Inputs.invalid("eventSeatId", "Select between 1 and 50 seats on the current page.");
        }
        TreeSet<Long> ids = new TreeSet<>();
        for (String value : values) {
            if (!ids.add(Inputs.id(value, "eventSeatId"))) {
                throw Inputs.invalid("eventSeatId", "Each selected inventory seat must be distinct.");
            }
        }
        return List.copyOf(ids);
    }
}
