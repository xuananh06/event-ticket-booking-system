package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Role;
import java.sql.*;
import static com.eventbooking.dao.JdbcAccess.*;

public final class ActorDao {
    private ActorDao() { }
    public static void require(Connection connection, long actor, Role role) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT role,is_active FROM dbo.users WITH (UPDLOCK,HOLDLOCK) WHERE id=?", actor); ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !rows.getBoolean("is_active") || !role.name().equals(rows.getString("role"))) {
                throw DomainException.forbidden();
            }
        }
    }
}
