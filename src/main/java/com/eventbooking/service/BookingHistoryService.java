package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.model.*;

public final class BookingHistoryService {
    private final JdbcAccess database;
    private final BookingDao bookings;
    private final PaymentDao payments;
    private final BookingHistoryDao history;

    public BookingHistoryService(JdbcAccess database, BookingDao bookings, PaymentDao payments, BookingHistoryDao history) {
        this.database = database; this.bookings = bookings; this.payments = payments; this.history = history;
    }

    public Page<BookingHistory> list(long customer, String status, int page, int size) {
        String filter = PaymentRules.status(status);
        return database.read(c -> {
            ActorDao.require(c, customer, Role.CUSTOMER);
            return history.list(c, customer, filter, new EventDao().now(c), page, size);
        });
    }

    public record Detail(BookingService.Checkout summary, Page<Payment> attempts, boolean canPay, boolean canCancel, Payment recorded) { }

    public Detail detail(long customer, long booking, int page, int size, Long recorded) {
        return database.read(c -> {
            ActorDao.require(c, customer, Role.CUSTOMER);
            Booking owned = bookings.owned(c, customer, booking, false);
            var now = new EventDao().now(c);
            return new Detail(new BookingService.Checkout(owned, bookings.items(c, owned.id()), now),
                    payments.page(c, owned.id(), page, size), history.canPay(c, owned, now), history.canCancel(c, owned, now),
                    recorded == null ? null : payments.attempt(c, owned.id(), recorded).orElse(null));
        });
    }
}
