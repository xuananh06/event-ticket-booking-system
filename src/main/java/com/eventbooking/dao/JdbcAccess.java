package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ServiceUnavailableException;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Shared connection/transaction mechanics; no repository abstraction or retained connection. */
public final class JdbcAccess {
    private final Optional<DataSource> source;
    public JdbcAccess(Optional<DataSource> source) { this.source = Objects.requireNonNull(source); }
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }
    public <T> T read(Work<T> work) {
        try (Connection connection = connect()) { return work.run(connection); }
        catch (SQLException | UnsatisfiedLinkError error) { throw mapped(error); }
    }
    public <T> T write(Work<T> work) {
        try (Connection connection = connect()) {
            int isolation = connection.getTransactionIsolation();
            boolean auto = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            boolean completed = false;
            try {
                // JDBC enables implicit transactions; a table SELECT starts one before sp_getapplock.
                try (PreparedStatement begin = statement(connection, "SELECT TOP(0) id FROM dbo.users");
                     ResultSet ignored = begin.executeQuery()) { }
                // Transaction-owned database lock serializes cross-table management policies across JVMs.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DECLARE @result int; EXEC @result=sys.sp_getapplock @Resource=N'EventBookingP3Management',@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=5000; SELECT @result")) {
                    statement.setQueryTimeout(10);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) < 0) { throw new SQLException("Management lock unavailable.", "40001", 1205); }
                    }
                }
                T result = work.run(connection);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); completed = true; } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                // A failed rollback must never be followed by setAutoCommit(true), which can commit.
                if (completed) {
                    connection.setAutoCommit(auto);
                    connection.setTransactionIsolation(isolation);
                }
            }
        } catch (SQLException | UnsatisfiedLinkError error) { throw mapped(error); }
    }
    private Connection connect() throws SQLException { return source.orElseThrow(() -> new SQLException("Database not configured.")).getConnection(); }
    private RuntimeException mapped(Throwable error) {
        if (error instanceof SQLException sql) {
            if (sql.getErrorCode() == 2601 || sql.getErrorCode() == 2627) { return DomainException.conflict("A record with these unique values already exists."); }
            if (sql.getErrorCode() == 547) { return DomainException.conflict("This change conflicts with related records."); }
            if (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1222) { return DomainException.conflict("Another change is in progress. Please retry."); }
        }
        return new ServiceUnavailableException();
    }
    public static PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(10);
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof String text) { statement.setNString(index + 1, text); }
                else { statement.setObject(index + 1, value); }
            }
            return statement;
        } catch (SQLException error) { statement.close(); throw error; }
    }
    public static long scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql, values); ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) { throw new SQLException("Expected scalar result."); }
            return rows.getLong(1);
        }
    }
    public static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql, values)) {
            if (statement.executeUpdate() != 1) { throw DomainException.missing(); }
        }
    }
}
