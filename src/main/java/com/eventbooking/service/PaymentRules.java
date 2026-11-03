package com.eventbooking.service;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Booking;
import com.eventbooking.model.BookingItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class PaymentRules {
    private PaymentRules() { }

    public static String single(String[] values, String field) {
        if (values == null || values.length != 1 || values[0] == null) {
            throw Inputs.invalid(field, "Submit exactly one " + field + " value.");
        }
        return values[0];
    }

    public static String key(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw Inputs.invalid("paymentKey", "Use the original payment form or open a new simulation attempt.");
        }
        return value;
    }

    public static String outcome(String value) {
        if (!"success".equals(value) && !"failure".equals(value)) {
            throw Inputs.invalid("outcome", "Choose a supported simulated payment outcome.");
        }
        return value;
    }

    public static String fingerprint(long booking, String outcome, BigDecimal amount) {
        outcome(outcome);
        BigDecimal exact = BookingRules.total(List.of(amount));
        String content = "payment:v1\nbooking=" + booking + "\noutcome=" + outcome + "\namount=" + exact.toPlainString() + "\ncurrency=VND";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Payment digest unavailable."); }
    }

    public static void items(Booking booking, List<BookingItem> items) {
        if (items.isEmpty() || items.size() > InventoryInputs.MAX_SELECTION
                || items.stream().map(BookingItem::eventSeatId).distinct().count() != items.size()
                || BookingRules.total(items.stream().map(BookingItem::unitPrice).toList()).compareTo(booking.total()) != 0) {
            throw DomainException.conflict("The reservation's stored items or total are inconsistent. No financial action was recorded.");
        }
    }

    public static String status(String value) {
        if (value == null || value.isEmpty()) { return ""; }
        if (!Set.of("PENDING", "CONFIRMED", "EXPIRED", "CANCELLED").contains(value)) {
            throw Inputs.invalid("status", "Choose a valid booking state.");
        }
        return value;
    }
}
