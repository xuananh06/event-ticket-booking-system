package com.eventbooking.service;

import com.eventbooking.dao.StatisticsDao;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.model.Role;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsServiceTest {
    @Test void missingOrFailedSourceNeverBecomesFabricatedZerosOrSensitiveDiagnostics() {
        DataSource failed = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> { throw new SQLException("private-connection-detail"); });
        for (Optional<DataSource> source : java.util.List.of(Optional.<DataSource>empty(), Optional.of(failed))) {
            for (Role role : Role.values()) {
                var error = assertThrows(ServiceUnavailableException.class,
                        () -> new StatisticsService(source, new StatisticsDao()).summary(1, role));
                assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
                assertFalse(error.toString().contains("private-connection-detail"));
            }
        }
    }
}
