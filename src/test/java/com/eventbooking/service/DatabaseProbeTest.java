package com.eventbooking.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import static com.eventbooking.service.DatabaseProbe.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseProbeTest {
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    @Test
    void noDatabaseIsAnExplicitState() {
        assertEquals(NOT_CONFIGURED, new DatabaseProbe(Optional.empty()).check());
    }

    @Test
    void eachProbeAcquiresAndClosesItsOwnResourcesInOrder() {
        List<String> actions = new ArrayList<>();
        DataSource source = stub(DataSource.class, (proxy, method, arguments) -> {
            assertEquals("getConnection", method.getName());
            actions.add("open");
            ResultSet rows = stub(ResultSet.class, (p, m, a) -> switch (m.getName()) {
                case "next" -> true;
                case "getInt" -> 1;
                case "close" -> {
                    actions.add("rows.close");
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
            PreparedStatement statement = stub(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
                case "setQueryTimeout" -> {
                    assertEquals(5, a[0]);
                    yield null;
                }
                case "executeQuery" -> rows;
                case "close" -> {
                    actions.add("statement.close");
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
            return stub(Connection.class, (p, m, a) -> switch (m.getName()) {
                case "prepareStatement" -> {
                    assertEquals("SELECT 1", a[0]);
                    yield statement;
                }
                case "close" -> {
                    actions.add("connection.close");
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        });
        DatabaseProbe probe = new DatabaseProbe(Optional.of(source));
        assertEquals(AVAILABLE, probe.check());
        assertEquals(AVAILABLE, probe.check());
        assertEquals(List.of("open", "rows.close", "statement.close", "connection.close",
                "open", "rows.close", "statement.close", "connection.close"), actions);
    }

    @Test
    void queryFailureStillClosesTheConnectionAndDoesNotExposeDriverDetails() {
        List<String> actions = new ArrayList<>();
        PreparedStatement statement = stub(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
            case "setQueryTimeout" -> null;
            case "executeQuery" -> throw new SQLException("secret JDBC details");
            case "close" -> {
                actions.add("statement.close");
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        Connection connection = stub(Connection.class, (p, m, a) -> switch (m.getName()) {
            case "prepareStatement" -> statement;
            case "close" -> {
                actions.add("connection.close");
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        DataSource source = stub(DataSource.class, (p, m, a) -> connection);
        assertEquals(UNAVAILABLE, new DatabaseProbe(Optional.of(source)).check());
        assertEquals(List.of("statement.close", "connection.close"), actions);
    }

    @Test
    void connectionFailureIsReportedAsUnavailable() {
        DataSource source = stub(DataSource.class, (p, m, a) -> {
            throw new SQLException("secret");
        });
        assertEquals(UNAVAILABLE, new DatabaseProbe(Optional.of(source)).check());
    }
}
