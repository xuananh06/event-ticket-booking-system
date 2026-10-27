package com.eventbooking.model;

import java.time.LocalDateTime;

/**
 * Informational state only; reading never allocates or rewrites inventory.
 */
public enum SeatAvailability {
    AVAILABLE, HELD, UNAVAILABLE;

    public static SeatAvailability at(boolean eligiblePhysicalSeat, String storedState, LocalDateTime expiry, LocalDateTime now) {
        if (!eligiblePhysicalSeat) {
            return UNAVAILABLE;
        }
        if ("AVAILABLE".equals(storedState)) {
            return AVAILABLE;
        }
        if ("HELD".equals(storedState) && expiry != null) {
            return expiry.isAfter(now) ? HELD : AVAILABLE;
        }
        return UNAVAILABLE;
    }
}
