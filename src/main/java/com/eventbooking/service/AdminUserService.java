package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.model.*;
import com.eventbooking.security.PasswordHasher;

import java.sql.*;

public final class AdminUserService {
    private final JdbcAccess database;
    private final AdminUserDao users;
    private final PasswordHasher passwords;

    public AdminUserService(JdbcAccess database, AdminUserDao users, PasswordHasher passwords) {
        this.database = database;
        this.users = users;
        this.passwords = passwords;
    }

    public static Role role(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw Inputs.invalid("role", "Choose a valid role.");
        }
    }

    public Page<ManagedUser> list(long actor, String search, Boolean active, int page, int size) {
        String query = search == null ? "" : search.strip();
        if (query.length() > 100 || query.codePoints().anyMatch(Character::isISOControl)) {
            throw Inputs.invalid("q", "Search must be at most 100 characters without controls.");
        }
        return database.read(c -> {
            ActorDao.require(c, actor, Role.ADMIN);
            return users.list(c, query, active, page, size);
        });
    }

    public ManagedUser find(long actor, long id) {
        return database.read(c -> {
            ActorDao.require(c, actor, Role.ADMIN);
            return users.find(c, id);
        });
    }

    public long create(long actor, String name, String email, String password, String confirmation, Role role, boolean active) {
        AccountValidation.Fields fields = AccountValidation.registration(name, email, password, confirmation, passwords);
        if (role == null) {
            throw Inputs.invalid("role", "Choose a valid role.");
        }
        String hash = passwords.hash(password);
        return database.write(c -> {
            ActorDao.require(c, actor, Role.ADMIN);
            return users.create(c, fields.name(), fields.email(), hash, role, active);
        });
    }

    public void save(long actor, long id, String name, String email, Role role, boolean active) {
        AccountValidation.Fields fields = AccountValidation.profile(name, email);
        if (role == null) {
            throw Inputs.invalid("role", "Choose a valid role.");
        }
        database.write(c -> {
            ActorDao.require(c, actor, Role.ADMIN);
            ManagedUser previous = users.find(c, id);
            guard(c, actor, previous, role, active, false);
            users.save(c, id, fields.name(), fields.email(), role, active);
            return null;
        });
    }

    public void status(long actor, long id, boolean active) {
        database.write(c -> {
            ActorDao.require(c, actor, Role.ADMIN);
            ManagedUser previous = users.find(c, id);
            guard(c, actor, previous, previous.role(), active, false);
            users.save(c, id, previous.fullName(), previous.email(), previous.role(), active);
            return null;
        });
    }

    public void delete(long actor, long id) {
        database.write(c -> {
            ActorDao.require(c, actor, Role.ADMIN);
            ManagedUser previous = users.find(c, id);
            guard(c, actor, previous, previous.role(), previous.active(), true);
            users.delete(c, id);
            return null;
        });
    }

    private void guard(Connection c, long actor, ManagedUser target, Role role, boolean active, boolean deleting) throws SQLException {
        AdminAccountPolicy.change(actor, target.id(), target.role(), target.active(), role, active, deleting, users.activeAdmins(c), users.events(c, target.id()), users.bookings(c, target.id()));
    }
}
