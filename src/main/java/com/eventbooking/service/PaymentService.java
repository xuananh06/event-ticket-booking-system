package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;
import java.sql.Connection;
import java.sql.SQLException;

public final class PaymentService {
    private final JdbcAccess database;
    private final BookingDao bookings;
    private final PaymentDao payments;

    public PaymentService(JdbcAccess database, BookingDao bookings, PaymentDao payments) {
        this.database = database; this.bookings = bookings; this.payments = payments;
    }

    public record Result(Payment attempt, boolean replay) { }

    public Result pay(long customer, long booking, String key, String outcome) {
        PaymentRules.key(key); PaymentRules.outcome(outcome);
        return database.write(c -> pay(c, customer, booking, key, outcome));
    }

    // Package scope permits a test-only bridge to exercise this exact work on independent transactions.
    Result pay(Connection c, long customer, long bookingId, String key, String outcome) throws SQLException {
        PaymentRules.key(key); PaymentRules.outcome(outcome);
        Booking booking = bookings.owned(c, customer, bookingId, true);
        ActorDao.require(c, customer, Role.CUSTOMER);
        String fingerprint = PaymentRules.fingerprint(booking.id(), outcome, booking.total());
        var previous = payments.find(c, booking.id(), key);
        if (previous.isPresent()) {
            Payment attempt = previous.orElseThrow();
            if (!attempt.fingerprint().equals(fingerprint)) {
                throw DomainException.conflict("This payment key already belongs to a different simulated request.");
            }
            return new Result(attempt, true);
        }
        if (!booking.status().equals("PENDING") || payments.successful(c, booking.id()).isPresent()) {
            throw DomainException.conflict("This reservation cannot accept a new payment attempt.");
        }
        bookings.saleEvent(c, booking.eventId());
        var items = bookings.items(c, booking.id());
        PaymentRules.items(booking, items);
        payments.lockAllocation(c, booking, items, "HELD", true);
        bookings.saleEvent(c, booking.eventId());
        payments.verifyLive(c, booking);
        var at = payments.millisNow(c);
        Payment attempt = payments.record(c, booking, key, fingerprint, outcome, at);
        if (outcome.equals("success")) {
            for (var item : items) { payments.sell(c, booking, item.eventSeatId()); }
            payments.confirm(c, booking, at);
        } else {
            payments.verifyLive(c, booking);
        }
        return new Result(attempt, false);
    }

    public record Cancellation(Booking booking, boolean repeated) { }

    public Cancellation cancel(long customer, long booking) {
        return database.write(c -> cancel(c, customer, booking));
    }

    Cancellation cancel(Connection c, long customer, long bookingId) throws SQLException {
        Booking booking = bookings.owned(c, customer, bookingId, true);
        ActorDao.require(c, customer, Role.CUSTOMER);
        if (booking.status().equals("CANCELLED")) { return new Cancellation(booking, true); }
        if (!booking.status().equals("PENDING") && !booking.status().equals("CONFIRMED")) {
            throw DomainException.conflict("This reservation cannot be cancelled. Review its current state.");
        }
        var success = payments.successful(c, booking.id());
        boolean confirmed = booking.status().equals("CONFIRMED");
        if (confirmed) {
            Payment payment = success.orElseThrow(() -> DomainException.conflict("The successful payment is missing. No refund was recorded."));
            if (!payment.status().equals("SUCCEEDED") || payment.amount().compareTo(booking.total()) != 0
                    || booking.confirmedAt() == null || !booking.confirmedAt().equals(payment.succeededAt())) {
                throw DomainException.conflict("The reservation and payment are inconsistent. No refund was recorded.");
            }
        } else if (success.isPresent()) {
            throw DomainException.conflict("The reservation and payment are inconsistent. No cancellation was recorded.");
        }
        payments.lockCancellationEvent(c, booking);
        var items = bookings.items(c, booking.id());
        PaymentRules.items(booking, items);
        String state = confirmed ? "SOLD" : "HELD";
        payments.lockAllocation(c, booking, items, state, false);
        payments.verifyCancellation(c, booking);
        var at = payments.millisNow(c);
        if (confirmed) { payments.refund(c, booking, success.orElseThrow(), at); }
        for (var item : items) { payments.release(c, booking, item.eventSeatId(), state); }
        payments.cancel(c, booking, at);
        return new Cancellation(bookings.owned(c, customer, booking.id(), false), false);
    }
}
