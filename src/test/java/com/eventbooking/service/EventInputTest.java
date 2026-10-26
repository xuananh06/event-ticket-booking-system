package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.exception.*;
import com.eventbooking.model.EventFilter;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventInputTest {
    @Test void localTimesAreExplicitlyConvertedAndRejectUnsupportedValues() {
        LocalDateTime utc=EventTimes.utc("2030-03-04T09:30:15.123","startsAt");
        assertEquals(LocalDateTime.of(2030,3,4,2,30,15,123000000),utc);
        assertEquals("2030-03-04T09:30:15.123",EventTimes.input(utc));
        for(String value:new String[]{"invalid","2030-02-30T10:00","2030-01-01T10:00Z","2030-01-01T10:00:00.0001","0001-01-01T00:00","+10000-01-01T01:00"}){
            assertThrows(ValidationException.class,()->EventTimes.utc(value,"startsAt"));
        }
        assertThrows(ValidationException.class,()->EventFilter.parse("","","","2030-02-03","2030-02-02","","1","10"));
        assertThrows(ValidationException.class,()->EventFilter.parse("","","","","","OTHER","1","10"));
    }
    @Test void malformedDraftsFailBeforeDatabaseAndValidInputFailsSafelyWithoutDatabase() {
        EventService events=new EventService(new JdbcAccess(Optional.empty()),new EventDao());
        assertThrows(ValidationException.class,()->events.save(1,null,1,1,"Event","x".repeat(10001),"2030-01-01T10:00","2030-01-01T11:00"));
        assertThrows(ValidationException.class,()->events.save(1,null,1,1,"Event","Text","2030-01-01T11:00","2030-01-01T10:00"));
        assertThrows(ServiceUnavailableException.class,()->events.save(1,null,1,1,"Event","Text","2030-01-01T10:00","2030-01-01T11:00"));
    }
}
