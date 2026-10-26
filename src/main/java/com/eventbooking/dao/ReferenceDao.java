package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;

import java.sql.*;
import java.util.*;

import static com.eventbooking.dao.JdbcAccess.*;

public final class ReferenceDao {
    public Category category(Connection c, long id) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT id,name,is_active FROM dbo.categories WITH (UPDLOCK,HOLDLOCK) WHERE id=?", id); ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                throw DomainException.missing();
            }
            return category(r);
        }
    }

    public Venue venue(Connection c, long id) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT id,name,address,is_active FROM dbo.venues WITH (UPDLOCK,HOLDLOCK) WHERE id=?", id); ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                throw DomainException.missing();
            }
            return venue(r);
        }
    }

    public Seat seat(Connection c, long id) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT s.id,s.venue_id,v.name AS venue_name,s.row_label,s.seat_number,s.is_active FROM dbo.seats s WITH (UPDLOCK,HOLDLOCK) JOIN dbo.venues v ON v.id=s.venue_id WHERE s.id=?", id); ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                throw DomainException.missing();
            }
            return seat(r);
        }
    }

    public Page<Category> categories(Connection c, Boolean active, int page, int size) throws SQLException {
        String where = active == null ? "" : " WHERE is_active=?";
        Object[] parameters = active == null ? new Object[]{} : new Object[]{active};
        long total = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.categories" + where, parameters);
        List<Category> rows = new ArrayList<>();
        List<Object> values = new ArrayList<>(Arrays.asList(parameters));
        values.add(Page.offset(page, size));
        values.add(size);
        try (PreparedStatement s = statement(c, "SELECT id,name,is_active FROM dbo.categories" + where + " ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", values.toArray()); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                rows.add(category(r));
            }
        }
        return new Page<>(rows, total, page, size);
    }

    public Page<Venue> venues(Connection c, Boolean active, int page, int size) throws SQLException {
        String where = active == null ? "" : " WHERE is_active=?";
        Object[] parameters = active == null ? new Object[]{} : new Object[]{active};
        long total = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.venues" + where, parameters);
        List<Venue> rows = new ArrayList<>();
        List<Object> values = new ArrayList<>(Arrays.asList(parameters));
        values.add(Page.offset(page, size));
        values.add(size);
        try (PreparedStatement s = statement(c, "SELECT id,name,address,is_active FROM dbo.venues" + where + " ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", values.toArray()); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                rows.add(venue(r));
            }
        }
        return new Page<>(rows, total, page, size);
    }

    public Page<Seat> seats(Connection c, Long venueId, Boolean active, int page, int size) throws SQLException {
        String where = " WHERE 1=1";
        List<Object> values = new ArrayList<>();
        if (venueId != null) {
            where += " AND s.venue_id=?";
            values.add(venueId);
        }
        if (active != null) {
            where += " AND s.is_active=?";
            values.add(active);
        }
        long total = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.seats s" + where, values.toArray());
        values.add(Page.offset(page, size));
        values.add(size);
        List<Seat> rows = new ArrayList<>();
        try (PreparedStatement s = statement(c, "SELECT s.id,s.venue_id,v.name AS venue_name,s.row_label,s.seat_number,s.is_active FROM dbo.seats s JOIN dbo.venues v ON v.id=s.venue_id" + where + " ORDER BY s.venue_id,s.row_label,s.seat_number,s.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", values.toArray()); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                rows.add(seat(r));
            }
        }
        return new Page<>(rows, total, page, size);
    }

    public long saveCategory(Connection c, Long id, String name, boolean active) throws SQLException {
        if (id == null) {
            return scalar(c, "INSERT dbo.categories(name,is_active) OUTPUT INSERTED.id VALUES (?,?)", name, active);
        }
        update(c, "UPDATE dbo.categories SET name=?,is_active=? WHERE id=?", name, active, id);
        return id;
    }

    public long saveVenue(Connection c, Long id, String name, String address, boolean active) throws SQLException {
        if (id == null) {
            return scalar(c, "INSERT dbo.venues(name,address,is_active) OUTPUT INSERTED.id VALUES (?,?,?)", name, address, active);
        }
        update(c, "UPDATE dbo.venues SET name=?,address=?,is_active=? WHERE id=?", name, address, active, id);
        return id;
    }

    public long saveSeat(Connection c, Long id, long venue, String row, int number, boolean active) throws SQLException {
        if (id == null) {
            return scalar(c, "INSERT dbo.seats(venue_id,row_label,seat_number,is_active) OUTPUT INSERTED.id VALUES (?,?,?,?)", venue, row, number, active);
        }
        update(c, "UPDATE dbo.seats SET venue_id=?,row_label=?,seat_number=?,is_active=? WHERE id=?", venue, row, number, active, id);
        return id;
    }

    public void categoryMayDisable(Connection c, long id) throws SQLException {
        if (scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events WHERE category_id=? AND status='PUBLISHED' AND starts_at>SYSUTCDATETIME()", id) > 0) {
            throw DomainException.conflict("An upcoming published event requires this category.");
        }
    }

    public void venueMayDisable(Connection c, long id) throws SQLException {
        if (scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events WHERE venue_id=? AND status='PUBLISHED' AND starts_at>SYSUTCDATETIME()", id) > 0) {
            throw DomainException.conflict("An upcoming published event requires this venue.");
        }
    }

    public boolean seatReferenced(Connection c, long id) throws SQLException {
        return scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE seat_id=?", id) > 0;
    }

    public void deleteCategory(Connection c, long id) throws SQLException {
        update(c, "DELETE dbo.categories WHERE id=?", id);
    }

    public void deleteVenue(Connection c, long id) throws SQLException {
        update(c, "DELETE dbo.venues WHERE id=?", id);
    }

    public void deleteSeat(Connection c, long id) throws SQLException {
        update(c, "DELETE dbo.seats WHERE id=?", id);
    }

    private Category category(ResultSet r) throws SQLException {
        return new Category(r.getLong("id"), r.getNString("name"), r.getBoolean("is_active"));
    }

    private Venue venue(ResultSet r) throws SQLException {
        return new Venue(r.getLong("id"), r.getNString("name"), r.getNString("address"), r.getBoolean("is_active"));
    }

    private Seat seat(ResultSet r) throws SQLException {
        return new Seat(r.getLong("id"), r.getLong("venue_id"), r.getNString("venue_name"), r.getNString("row_label"), r.getInt("seat_number"), r.getBoolean("is_active"));
    }
}
