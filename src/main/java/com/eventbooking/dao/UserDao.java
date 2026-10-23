package com.eventbooking.dao;

import com.eventbooking.model.Role;
import com.eventbooking.model.UserAccount;
import com.eventbooking.model.UserProfile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

public class UserDao {
    private final Optional<DataSource> source;

    public UserDao(Optional<DataSource> source) {
        this.source = java.util.Objects.requireNonNull(source);
    }

    private Connection connect() throws SQLException {
        return source.orElseThrow(() -> new SQLException("Database is not configured.")).getConnection();
    }

    public Optional<UserAccount> findByEmail(String email) throws SQLException {
        return find("SELECT id,email,full_name,role,is_active,password_hash FROM dbo.users WHERE email=?", email, true);
    }

    public Optional<UserAccount> findById(long id) throws SQLException {
        // Protected requests do not need to read the password hash.
        return find("SELECT id,email,full_name,role,is_active FROM dbo.users WHERE id=?", id, false);
    }

    private Optional<UserAccount> find(String sql, Object key, boolean credentials) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            if (key instanceof Long id) { statement.setLong(1, id); }
            else { statement.setNString(1, (String) key); }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { return Optional.empty(); }
                Role role;
                try { role = Role.valueOf(rows.getString("role")); }
                catch (IllegalArgumentException invalidRole) { throw new SQLException("Invalid account state."); }
                UserProfile profile = new UserProfile(rows.getLong("id"), rows.getNString("email"), rows.getNString("full_name"), role);
                return Optional.of(new UserAccount(profile, rows.getBoolean("is_active"), credentials ? rows.getString("password_hash") : null));
            }
        }
    }

    public long createCustomer(String fullName, String email, String passwordHash) throws SQLException {
        String sql = "INSERT dbo.users(full_name,email,password_hash,role,is_active) OUTPUT INSERTED.id VALUES (?,?,?,'CUSTOMER',1)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setNString(1, fullName);
            statement.setNString(2, email);
            statement.setString(3, passwordHash);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Account creation did not return an identifier."); }
                return rows.getLong(1);
            }
        }
    }
}
