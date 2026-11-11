package com.eventbooking.dao;

import com.eventbooking.model.StatisticsSummary;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static com.eventbooking.dao.JdbcAccess.*;

/** Read-only aggregates at booking, item and payment grains; no public-catalog predicates. */
public final class StatisticsDao {
    public StatisticsSummary.Customer customer(Connection c, long customer, LocalDateTime now) throws SQLException {
        try (var s = statement(c, "SELECT COUNT_BIG(*) AS total,"
                + "COUNT_BIG(CASE WHEN status='PENDING' AND expires_at>? THEN 1 END) AS pending,"
                + "COUNT_BIG(CASE WHEN status='CONFIRMED' THEN 1 END) AS confirmed,"
                + "COUNT_BIG(CASE WHEN status='EXPIRED' OR (status='PENDING' AND expires_at<=?) THEN 1 END) AS expired,"
                + "COUNT_BIG(CASE WHEN status='CANCELLED' THEN 1 END) AS cancelled "
                + "FROM dbo.bookings WHERE customer_id=?", now, now, customer); var r = s.executeQuery()) {
            r.next();
            return new StatisticsSummary.Customer(r.getLong("total"), r.getLong("pending"),
                    r.getLong("confirmed"), r.getLong("expired"), r.getLong("cancelled"));
        }
    }

    public StatisticsSummary.Organizer organizer(Connection c, long organizer, LocalDateTime now) throws SQLException {
        long total, upcoming;
        try (var s = statement(c, "SELECT COUNT_BIG(*),COUNT_BIG(CASE WHEN status='PUBLISHED' AND starts_at>? THEN 1 END) "
                + "FROM dbo.events WHERE organizer_id=?", now, organizer); var r = s.executeQuery()) {
            r.next(); total = r.getLong(1); upcoming = r.getLong(2);
        }
        return new StatisticsSummary.Organizer(total, upcoming, sales(c, organizer));
    }

    public StatisticsSummary.Admin admin(Connection c) throws SQLException {
        long accounts = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.users");
        long events = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.events");
        long bookings = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.bookings");
        return new StatisticsSummary.Admin(accounts, events, bookings, sales(c, null));
    }

    private StatisticsSummary.Sales sales(Connection c, Long organizer) throws SQLException {
        String scopeJoin = organizer == null ? "" : " JOIN dbo.events e ON e.id=b.event_id";
        String scope = organizer == null ? "" : " AND e.organizer_id=?";
        Object[] arguments = organizer == null ? new Object[0] : new Object[]{organizer};
        long tickets = scalar(c, "SELECT COUNT_BIG(*) FROM dbo.booking_items bi JOIN dbo.bookings b ON b.id=bi.booking_id"
                + scopeJoin + " WHERE b.status='CONFIRMED'" + scope, arguments);
        // A successful row remains in gross after its status is changed to REFUNDED.
        try (var s = statement(c,
                "SELECT COALESCE(SUM(CASE WHEN p.succeeded_at IS NOT NULL THEN p.amount ELSE 0 END),0),"
                        + "COALESCE(SUM(CASE WHEN p.status='REFUNDED' THEN p.amount ELSE 0 END),0) "
                        + "FROM dbo.payments p JOIN dbo.bookings b ON b.id=p.booking_id" + scopeJoin + " WHERE 1=1" + scope,
                arguments); var r = s.executeQuery()) {
            r.next();
            return new StatisticsSummary.Sales(tickets, r.getBigDecimal(1), r.getBigDecimal(2));
        }
    }
}
