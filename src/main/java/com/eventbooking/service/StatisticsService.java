package com.eventbooking.service;

import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.StatisticsDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.model.Role;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import static com.eventbooking.dao.JdbcAccess.statement;

/** One bounded, serializable read transaction per summary; never takes the management write lock. */
public final class StatisticsService {
    private final Optional<DataSource> source;
    private final StatisticsDao statistics;

    public StatisticsService(Optional<DataSource> source, StatisticsDao statistics) {
        this.source = Objects.requireNonNull(source);
        this.statistics = Objects.requireNonNull(statistics);
    }

    public Object summary(long account, Role expectedRole) {
        Objects.requireNonNull(expectedRole);
        try (Connection c = source.orElseThrow(ServiceUnavailableException::new).getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                // A plain SQL batch keeps the session setting outside prepared-RPC scope.
                try (var s = c.createStatement()) { s.setQueryTimeout(10); s.execute("SET LOCK_TIMEOUT 5000"); }
                // Shared serializable locks revalidate the actor without write-oriented UPDLOCK hints.
                try (var s = statement(c, "SELECT role,is_active FROM dbo.users WHERE id=?", account); var r = s.executeQuery()) {
                    if (!r.next() || !r.getBoolean("is_active") || !expectedRole.name().equals(r.getString("role"))) {
                        throw DomainException.forbidden();
                    }
                }
                var now = new EventDao().now(c);
                Object result = switch (expectedRole) {
                    case CUSTOMER -> statistics.customer(c, account, now);
                    case ORGANIZER -> statistics.organizer(c, account, now);
                    case ADMIN -> statistics.admin(c);
                };
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { c.rollback(); }
                catch (SQLException rollback) { throw new ServiceUnavailableException(); }
                // Never toggle auto-commit after a failed read; closing owns the connection lifecycle.
                throw failure;
            }
        } catch (DomainException rejected) {
            // Discard any JDBC close exception attached by try-with-resources.
            throw new DomainException(rejected.getStatus(), rejected.getMessage());
        } catch (SQLException | RuntimeException | UnsatisfiedLinkError unavailable) {
            throw new ServiceUnavailableException();
        }
    }
}
