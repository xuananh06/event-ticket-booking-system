package com.eventbooking.config;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;

import javax.sql.DataSource;

public final class DataSourceFactory {
    private DataSourceFactory() {
    }

    public static DataSource create(DatabaseSettings settings) {
        SQLServerDataSource source = new SQLServerDataSource();
        // Typed setters prevent configuration values from injecting JDBC URL properties.
        source.setServerName(settings.value("EVENT_DB_HOST", ""));
        source.setPortNumber(settings.port());
        source.setDatabaseName(settings.value("EVENT_DB_NAME", ""));
        source.setApplicationName("EventBooking");
        source.setEncrypt(Boolean.toString(settings.encrypt()));
        source.setTrustServerCertificate(settings.trustServerCertificate());
        source.setLoginTimeout(5);
        source.setSocketTimeout(5000);
        if (settings.value("EVENT_DB_AUTH", "sql-password").equals("windows-integrated")) {
            source.setIntegratedSecurity(true);
        } else {
            source.setUser(settings.value("EVENT_DB_USER", ""));
            source.setPassword(settings.value("EVENT_DB_PASSWORD", ""));
        }
        // SQLServerDataSource is not a pool and owns no persistent connection.
        return source;
    }
}
