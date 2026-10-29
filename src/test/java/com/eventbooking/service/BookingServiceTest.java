package com.eventbooking.service;

import com.eventbooking.dao.BookingDao;
import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class BookingServiceTest {
    @Test void validHoldWithoutDatabaseFailsGenerically() {
        var service = new BookingService(new JdbcAccess(Optional.empty()), new BookingDao());
        assertThrows(ServiceUnavailableException.class, () -> service.hold(1, 1, new String[]{"1"}));
        assertThrows(ServiceUnavailableException.class, () -> service.checkout(1, 1));
        assertThrows(ServiceUnavailableException.class, () -> service.expire(1, 1));
        assertThrows(ServiceUnavailableException.class, () -> service.selection(1, 1, 1, 10));
    }
}
