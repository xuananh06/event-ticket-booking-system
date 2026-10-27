package com.eventbooking.model;

import java.math.BigDecimal;

/**
 * Safe view projection: contains no booking owner, deadline or customer information.
 */
public record InventorySeat(long id, long seatId, String rowLabel, int seatNumber, BigDecimal price, String state) {
    public long getId() {
        return id;
    }

    public long getSeatId() {
        return seatId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public String getPriceLabel() {
        return price.toPlainString();
    }

    public String getState() {
        return state;
    }
}
