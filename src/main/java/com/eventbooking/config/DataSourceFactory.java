package com.eventbooking.config;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;

import javax.sql.DataSource;

public final class DataSourceFactory {
    private DataSourceFactory() {
    }

    public static DataSource create(DatabaseSettings settings) {
        SQLServerDataSource source = new SQLServerDataSource();
        // Typed setters prevent configuration values from injecting JDBC URL properties.
        source.setServerName(settings.value("db.host", ""));
        source.setPortNumber(settings.port());
        source.setDatabaseName(settings.databaseName());
        source.setApplicationName("EventBooking");
        source.setEncrypt(Boolean.toString(settings.encrypt()));
        source.setTrustServerCertificate(settings.trustServerCertificate());
        source.setLoginTimeout(5);
        source.setSocketTimeout(5000);
        source.setAuthentication("SqlPassword");
        source.setUser(settings.value("db.user", ""));
        source.setPassword(settings.value("db.password", ""));
        // SQLServerDataSource is not a pool and owns no persistent connection.
        return source;
    }
}
