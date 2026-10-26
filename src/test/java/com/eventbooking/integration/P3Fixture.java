package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.service.ReferenceService;

import java.sql.*;
import java.util.*;

import static com.eventbooking.dao.JdbcAccess.*;

/**
 * Owns exact fixture IDs; cleanup never targets seeded or unrelated rows.
 */
final class P3Fixture implements AutoCloseable {
    static final String PASSWORD = "Fixture account!123";
    final TestDatabase db = new TestDatabase();
    final JdbcAccess access = new JdbcAccess(Optional.of(db.source));
    final ReferenceService references = new ReferenceService(access, new ReferenceDao());
    final long admin;
    final List<Long> categories = new ArrayList<>(), venues = new ArrayList<>(), seats = new ArrayList<>(), events = new ArrayList<>(), bookings = new ArrayList<>();
    final String prefix = "Fixture " + UUID.randomUUID() + " ";

    P3Fixture() throws Exception {
        admin = user("ADMIN");
    }

    long user(String role) throws Exception {
        String email = db.email();
        long id = db.auth.register("Fixture Member", email, PASSWORD, PASSWORD);
        db.changeAccount(email, role, true);
        return id;
    }

    String email(long id) throws Exception {
        try (Connection c = db.source.getConnection(); PreparedStatement s = statement(c, "SELECT email FROM dbo.users WHERE id=?", id); ResultSet r = s.executeQuery()) {
            r.next();
            return r.getNString(1);
        }
    }

    long category() {
        long id = references.saveCategory(admin, null, prefix + categories.size(), true);
        categories.add(id);
        return id;
    }

    long venue() {
        long id = references.saveVenue(admin, null, prefix + venues.size(), "Campus", true);
        venues.add(id);
        return id;
    }

    long seat(long venue) {
        long id = references.saveSeat(admin, null, venue, "A", seats.size() + 1, true);
        seats.add(id);
        return id;
    }

    long event(long organizer, long category, long venue, String title, String status, int days) {
        long id = access.write(c -> scalar(c, "INSERT dbo.events(organizer_id,category_id,venue_id,title,description,starts_at,ends_at,status) OUTPUT INSERTED.id VALUES (?,?,?,?,?,DATEADD(DAY,?,SYSUTCDATETIME()),DATEADD(HOUR,2,DATEADD(DAY,?,SYSUTCDATETIME())),?)", organizer, category, venue, title, "Fixture description", days, days, status));
        events.add(id);
        return id;
    }

    void inventory(long event, long seat, long venue) {
        access.write(c -> {
            scalar(c, "INSERT dbo.event_seats(event_id,seat_id,venue_id,price) OUTPUT INSERTED.id VALUES (?,?,?,100)", event, seat, venue);
            return null;
        });
    }

    @Override
    public void close() throws Exception {
        try (Connection c = db.source.getConnection()) {
            for (long id : bookings) {
                update(c, "DELETE dbo.bookings WHERE id=?", id);
            }
            for (long id : events) {
                try (PreparedStatement s = statement(c, "DELETE dbo.event_seats WHERE event_id=?", id)) {
                    s.executeUpdate();
                }
                update(c, "DELETE dbo.events WHERE id=?", id);
            }
            for (long id : seats) {
                try (PreparedStatement s = statement(c, "DELETE dbo.seats WHERE id=?", id)) {
                    s.executeUpdate();
                }
            }
            for (long id : venues) {
                try (PreparedStatement s = statement(c, "DELETE dbo.venues WHERE id=?", id)) {
                    s.executeUpdate();
                }
            }
            for (long id : categories) {
                try (PreparedStatement s = statement(c, "DELETE dbo.categories WHERE id=?", id)) {
                    s.executeUpdate();
                }
            }
        } finally {
            db.close();
        }
    }
}
