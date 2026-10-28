package com.eventbooking.service;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;

/** Exact money, immutable item labels, and the inclusive expiration boundary. */
public final class BookingRules {
    private BookingRules() { }

    public static BigDecimal total(Collection<BigDecimal> prices) {
        BigDecimal total = new BigDecimal("0.00");
        for (BigDecimal price : prices) {
            if (price == null || price.signum() < 0 || price.scale() > 2) {
                throw DomainException.conflict("A selected seat has an unsupported price.");
            }
            total = total.add(price);
            if (total.compareTo(InventoryInputs.MAX_PRICE) > 0) {
                throw DomainException.conflict("The selected total exceeds the supported VND amount. Select fewer seats.");
            }
        }
        return total.setScale(2);
    }

    public static String seatLabel(String row, int number) {
        if (row == null || row.isBlank() || row.length() > 10 || number <= 0
                || row.codePoints().anyMatch(Character::isISOControl)) {
            throw DomainException.conflict("A selected seat has an invalid label.");
        }
        return "Row " + row + " / " + number;
    }

    public static String effectiveStatus(String stored, LocalDateTime expires, LocalDateTime now) {
        return "PENDING".equals(stored) && !expires.isAfter(now) ? "EXPIRED" : stored;
    }

    public static void requireDetails(Event event) {
        String description = event.description();
        if (event.title() == null || event.title().isBlank() || event.title().length() > 200
                || event.title().codePoints().anyMatch(Character::isISOControl)
                || description == null || description.isBlank() || description.length() > 10000
                || description.codePoints().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
                || !event.endsAt().isAfter(event.startsAt())) {
            throw DomainException.conflict("This event is not available for reservations.");
        }
    }
}
