package com.eventbooking.service;

import java.sql.Connection;
import java.sql.SQLException;

/** Test-only access to the exact production transaction body without runtime switches. */
public final class PaymentTestWork {
    private PaymentTestWork() { }

    public static PaymentService.Result pay(PaymentService service, Connection connection,
                                           long customer, long booking, String key, String outcome) throws SQLException {
        return service.pay(connection, customer, booking, key, outcome);
    }

    public static PaymentService.Cancellation cancel(PaymentService service, Connection connection, long customer, long booking) throws SQLException {
        return service.cancel(connection, customer, booking);
    }
}
