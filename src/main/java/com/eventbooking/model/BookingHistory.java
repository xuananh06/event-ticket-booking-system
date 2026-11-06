package com.eventbooking.model;

import com.eventbooking.service.BookingRules;
import java.time.LocalDateTime;

/** Owner-filtered history row evaluated at the list's single SQL clock value. */
public record BookingHistory(Booking booking, int quantity, LocalDateTime now) {
    public Booking getBooking() { return booking; }
    public int getQuantity() { return quantity; }
    public String getState() { return BookingRules.effectiveStatus(booking.status(), booking.expiresAt(), now); }
}
