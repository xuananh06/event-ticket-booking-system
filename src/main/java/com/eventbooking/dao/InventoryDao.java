package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Event;
import com.eventbooking.model.InventorySeat;
import com.eventbooking.model.InventorySummary;
import com.eventbooking.model.Page;
import com.eventbooking.model.SeatAvailability;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.eventbooking.dao.JdbcAccess.*;

public final class InventoryDao {
    public record Checks(long activeSeats, long inventory, long invalidMembers, long missingSeats,
                         long nonPristine, long history) { }

    /** All callers making decisions use the existing SERIALIZABLE management transaction. */
    public Checks checks(Connection c, Event event) throws SQLException {
        long active = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.seats WITH (UPDLOCK,HOLDLOCK) WHERE venue_id=? AND is_active=1", event.venueId());
        long inventory;
        long invalid;
        long nonPristine;
        try (PreparedStatement s = statement(c, "SELECT COUNT_BIG(*),COALESCE(SUM(CASE WHEN s.is_active=0 OR s.venue_id<>? OR es.venue_id<>? THEN CONVERT(bigint,1) ELSE 0 END),0),COALESCE(SUM(CASE WHEN es.state<>'AVAILABLE' OR es.current_booking_id IS NOT NULL OR es.hold_expires_at IS NOT NULL THEN CONVERT(bigint,1) ELSE 0 END),0) FROM dbo.event_seats es WITH (UPDLOCK,HOLDLOCK) JOIN dbo.seats s ON s.id=es.seat_id WHERE es.event_id=?", event.venueId(), event.venueId(), event.id());
             ResultSet r = s.executeQuery()) {
            r.next();
            inventory = r.getLong(1);
            invalid = r.getLong(2);
            nonPristine = r.getLong(3);
        }
        long missing = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.seats s WHERE s.venue_id=? AND s.is_active=1 AND NOT EXISTS (SELECT 1 FROM dbo.event_seats es WHERE es.event_id=? AND es.seat_id=s.id)", event.venueId(), event.id());
        long history = scalar(c, "SELECT (SELECT COUNT_BIG(*) FROM dbo.bookings WITH (UPDLOCK,HOLDLOCK) WHERE event_id=?)+(SELECT COUNT_BIG(*) FROM dbo.booking_items WITH (UPDLOCK,HOLDLOCK) WHERE event_id=?)", event.id(), event.id());
        return new Checks(active, inventory, invalid, missing, nonPristine, history);
    }

    public int generate(Connection c, Event event, BigDecimal price) throws SQLException {
        try (PreparedStatement s = statement(c, "INSERT dbo.event_seats(event_id,seat_id,venue_id,price,state,current_booking_id,hold_expires_at) SELECT e.id,s.id,e.venue_id,?,'AVAILABLE',NULL,NULL FROM dbo.events e JOIN dbo.seats s ON s.venue_id=e.venue_id AND s.is_active=1 WHERE e.id=? AND e.organizer_id=? AND e.status='DRAFT' AND e.starts_at>SYSUTCDATETIME() AND e.ends_at>e.starts_at AND NOT EXISTS (SELECT 1 FROM dbo.event_seats es WHERE es.event_id=e.id AND es.seat_id=s.id)", price, event.id(), event.organizerId())) {
            return s.executeUpdate();
        }
    }

    public void requireMembers(Connection c, long event, List<Long> ids) throws SQLException {
        for (long id : ids) {
            if (scalar(c, "SELECT COUNT_BIG(*) FROM dbo.event_seats WITH (UPDLOCK,HOLDLOCK) WHERE event_id=? AND id=?", event, id) != 1) {
                throw DomainException.missing();
            }
        }
    }

    public void price(Connection c, Event event, List<Long> ids, BigDecimal price) throws SQLException {
        for (long id : ids) {
            try (PreparedStatement s = statement(c, "UPDATE es SET price=? FROM dbo.event_seats es JOIN dbo.events e ON e.id=es.event_id WHERE es.id=? AND es.event_id=? AND e.organizer_id=? AND e.status='DRAFT' AND e.starts_at>SYSUTCDATETIME() AND es.state='AVAILABLE' AND es.current_booking_id IS NULL AND es.hold_expires_at IS NULL", price, id, event.id(), event.organizerId())) {
                if (s.executeUpdate() != 1) {
                    throw DomainException.conflict("The event or selected inventory changed. Reopen the page and retry.");
                }
            }
        }
    }

    public void publish(Connection c, Event event) throws SQLException {
        try (PreparedStatement s = statement(c, "UPDATE e SET status='PUBLISHED' FROM dbo.events e JOIN dbo.users u ON u.id=e.organizer_id JOIN dbo.categories c ON c.id=e.category_id JOIN dbo.venues v ON v.id=e.venue_id WHERE e.id=? AND e.organizer_id=? AND e.status='DRAFT' AND u.role='ORGANIZER' AND u.is_active=1 AND c.is_active=1 AND v.is_active=1 AND e.starts_at>SYSUTCDATETIME() AND e.ends_at>e.starts_at", event.id(), event.organizerId())) {
            if (s.executeUpdate() != 1) {
                throw DomainException.conflict("The event is no longer eligible for publication.");
            }
        }
    }

    public Page<InventorySeat> page(Connection c, long event, LocalDateTime now, int page, int size) throws SQLException {
        return page(c, event, now, page, size, false);
    }

    public Page<InventorySeat> page(Connection c, long event, LocalDateTime now, int page, int size, boolean publicOnly) throws SQLException {
        String where = " WHERE es.event_id=?" + (publicOnly ? " AND " + EventDao.eligible() : "");
        List<Object> values = new ArrayList<>(); values.add(event); if (publicOnly) { values.add(now); }
        long total = scalar(c, "SELECT COUNT_BIG(*)" + joins(publicOnly) + where, values.toArray());
        values.add(Page.offset(page, size)); values.add(size);
        List<InventorySeat> seats = new ArrayList<>();
        try (PreparedStatement s = statement(c, "SELECT es.id,es.seat_id,s.row_label,s.seat_number,es.price,es.state,es.hold_expires_at,CASE WHEN s.is_active=1 AND s.venue_id=e.venue_id AND es.venue_id=e.venue_id THEN 1 ELSE 0 END AS physical_eligible" + joins(publicOnly) + where + " ORDER BY s.row_label COLLATE Latin1_General_100_BIN2,s.seat_number,es.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", values.toArray());
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String state = SeatAvailability.at(r.getBoolean("physical_eligible"), r.getString("state"), r.getObject("hold_expires_at", LocalDateTime.class), now).name();
                seats.add(new InventorySeat(r.getLong("id"), r.getLong("seat_id"), r.getNString("row_label"), r.getInt("seat_number"), r.getBigDecimal("price"), state));
            }
        }
        return new Page<>(seats, total, page, size);
    }

    public InventorySummary summary(Connection c, long event, LocalDateTime now) throws SQLException {
        return summary(c, event, now, false);
    }

    public InventorySummary summary(Connection c, long event, LocalDateTime now, boolean publicOnly) throws SQLException {
        List<Object> values = new ArrayList<>(); values.add(now); values.add(event); if (publicOnly) { values.add(now); }
        try (PreparedStatement s = statement(c, "WITH inventory AS (SELECT es.price," + effectiveState() + " AS availability" + joins(publicOnly) + " WHERE es.event_id=?" + (publicOnly ? " AND " + EventDao.eligible() : "") + ") SELECT COUNT_BIG(*),COALESCE(SUM(CASE WHEN availability='AVAILABLE' THEN CONVERT(bigint,1) ELSE 0 END),0),COALESCE(SUM(CASE WHEN availability='HELD' THEN CONVERT(bigint,1) ELSE 0 END),0),COALESCE(SUM(CASE WHEN availability='UNAVAILABLE' THEN CONVERT(bigint,1) ELSE 0 END),0),MIN(CASE WHEN availability='AVAILABLE' THEN price END),MAX(CASE WHEN availability='AVAILABLE' THEN price END) FROM inventory", values.toArray());
             ResultSet r = s.executeQuery()) {
            r.next();
            return new InventorySummary(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getBigDecimal(5), r.getBigDecimal(6));
        }
    }

    private static String effectiveState() {
        return "CASE WHEN s.is_active=0 OR s.venue_id<>e.venue_id OR es.venue_id<>e.venue_id THEN 'UNAVAILABLE' WHEN es.state='AVAILABLE' OR (es.state='HELD' AND es.hold_expires_at<=?) THEN 'AVAILABLE' WHEN es.state='HELD' AND es.hold_expires_at IS NOT NULL THEN 'HELD' ELSE 'UNAVAILABLE' END";
    }

    private static String joins(boolean publicOnly) {
        return " FROM dbo.event_seats es JOIN dbo.seats s ON s.id=es.seat_id JOIN dbo.events e ON e.id=es.event_id"
                + (publicOnly ? " JOIN dbo.users u ON u.id=e.organizer_id JOIN dbo.categories c ON c.id=e.category_id JOIN dbo.venues v ON v.id=e.venue_id" : "");
    }
}
