package com.eventbooking.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A bounded, read-only startup probe. No connection is retained or shared.
 */
public final class DatabaseProbe {
    public enum Status {NOT_CONFIGURED, AVAILABLE, UNAVAILABLE}

    private final Optional<DataSource> source;

    public DatabaseProbe(Optional<DataSource> source) {
        this.source = java.util.Objects.requireNonNull(source);
    }

    public Status check() {
        if (source.isEmpty()) {
            return Status.NOT_CONFIGURED;
        }
        try (Connection connection = source.orElseThrow().getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1 ? Status.AVAILABLE : Status.UNAVAILABLE;
            }
        } catch (SQLException | UnsatisfiedLinkError exception) {
            // Includes missing integrated-auth native library; never log driver details.
            return Status.UNAVAILABLE;
        }
    }
}
