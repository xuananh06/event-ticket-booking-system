package com.eventbooking.model;

import java.math.BigDecimal;

public record BookingItem(long eventSeatId, BigDecimal unitPrice, String seatLabel) {
    public long getEventSeatId() { return eventSeatId; }
    public String getPriceLabel() { return unitPrice.toPlainString(); }
    public String getSeatLabel() { return seatLabel; }
}
