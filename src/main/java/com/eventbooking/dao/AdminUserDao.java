package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;

import java.sql.*;
import java.util.*;

import static com.eventbooking.dao.JdbcAccess.*;

public final class AdminUserDao {
    public ManagedUser find(Connection c, long id) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT id,full_name,email,role,is_active FROM dbo.users WITH (UPDLOCK,HOLDLOCK) WHERE id=?", id); ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                throw DomainException.missing();
            }
            return map(r);
        }
    }

    public Page<ManagedUser> list(Connection c, String search, Boolean active, int page, int size) throws SQLException {
        String where = " WHERE (full_name LIKE ? ESCAPE '~' OR email LIKE ? ESCAPE '~')";
        String pattern = literal(search);
        List<Object> values = new ArrayList<>(List.of(pattern, pattern));
        if (active != null) {
            where += " AND is_active=?";
            values.add(active);
        }
        long total = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.users" + where, values.toArray());
        values.add(Page.offset(page, size));
        values.add(size);
        List<ManagedUser> rows = new ArrayList<>();
        try (PreparedStatement s = statement(c, "SELECT id,full_name,email,role,is_active FROM dbo.users" + where + " ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", values.toArray()); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                rows.add(map(r));
            }
        }
        return new Page<>(rows, total, page, size);
    }

    public static String literal(String text) {
        return "%" + text.replace("~", "~~").replace("%", "~%").replace("_", "~_").replace("[", "~[") + "%";
    }

    public long create(Connection c, String name, String email, String hash, Role role, boolean active) throws SQLException {
        return scalar(c, "INSERT dbo.users(full_name,email,password_hash,role,is_active) OUTPUT INSERTED.id VALUES (?,?,?,?,?)", name, email, hash, role.name(), active);
    }

    public void save(Connection c, long id, String name, String email, Role role, boolean active) throws SQLException {
        update(c, "UPDATE dbo.users SET full_name=?,email=?,role=?,is_active=? WHERE id=?", name, email, role.name(), active, id);
    }

    public long activeAdmins(Connection c) throws SQLException {
        return scalar(c, "SELECT COUNT_BIG(*) FROM dbo.users WITH (UPDLOCK,HOLDLOCK) WHERE role='ADMIN' AND is_active=1");
    }

    public long events(Connection c, long id) throws SQLException {
        return scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events WHERE organizer_id=?", id);
    }

    public long bookings(Connection c, long id) throws SQLException {
        return scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings WHERE customer_id=?", id);
    }

    public void delete(Connection c, long id) throws SQLException {
        update(c, "DELETE dbo.users WHERE id=?", id);
    }

    private ManagedUser map(ResultSet r) throws SQLException {
        return new ManagedUser(r.getLong("id"), r.getNString("full_name"), r.getNString("email"), Role.valueOf(r.getString("role")), r.getBoolean("is_active"));
    }
}
