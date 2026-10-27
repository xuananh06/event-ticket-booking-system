package com.eventbooking.model;

import java.math.BigDecimal;

public record InventorySummary(long total, long available, long held, long unavailable,
                               BigDecimal minimumPrice, BigDecimal maximumPrice) {
    public long getTotal() {
        return total;
    }

    public long getAvailable() {
        return available;
    }

    public long getHeld() {
        return held;
    }

    public long getUnavailable() {
        return unavailable;
    }

    public String getMinimumPriceLabel() {
        return minimumPrice == null ? "" : minimumPrice.toPlainString();
    }

    public String getMaximumPriceLabel() {
        return maximumPrice == null ? "" : maximumPrice.toPlainString();
    }
}
