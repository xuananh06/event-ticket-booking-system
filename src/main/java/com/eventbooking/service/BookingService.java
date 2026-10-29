package com.eventbooking.service;

import com.eventbooking.dao.ActorDao;
import com.eventbooking.dao.BookingDao;
import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Booking;
import com.eventbooking.model.BookingItem;
import com.eventbooking.model.Role;

import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;

public final class BookingService {
    private final JdbcAccess database;
    private final BookingDao bookings;

    public BookingService(JdbcAccess database, BookingDao bookings) {
        this.database = database;
        this.bookings = bookings;
    }

    public long hold(long customer, long event, String[] submitted) {
        var ids = InventoryInputs.selection(submitted);
        if (event <= 0) { throw Inputs.invalid("eventId", "Choose a valid event."); }
        return database.write(c -> {
            ActorDao.require(c, customer, Role.CUSTOMER);
            bookings.saleEvent(c, event);
            var booking = bookings.create(c, customer, event, "BK-" + UUID.randomUUID());
            var items = new ArrayList<BookingItem>();
            for (long id : ids) {
                BookingItem item = bookings.claim(c, customer, event, booking, id);
                bookings.addItem(c, booking.id(), event, item);
                items.add(item);
            }
            var total = BookingRules.total(items.stream().map(BookingItem::unitPrice).toList());
            // Locks and inventory work may have waited; never renew the original deadline.
            ActorDao.require(c, customer, Role.CUSTOMER);
            bookings.saleEvent(c, event);
            bookings.finish(c, customer, event, booking, total);
            return booking.id();
        });
    }

    public PublicInventoryService.View selection(long customer, long eventId, int page, int size) {
        return database.read(c -> {
            ActorDao.require(c, customer, Role.CUSTOMER);
            var events = new EventDao();
            var now = events.now(c);
            var event = events.published(c, eventId, now);
            BookingRules.requireDetails(event);
            var inventory = new InventoryDao();
            return new PublicInventoryService.View(event, inventory.page(c, eventId, now, page, size, true), inventory.summary(c, eventId, now, true));
        });
    }

    public record Checkout(Booking booking, List<BookingItem> items, LocalDateTime now) {
        public Checkout { items = List.copyOf(items); }
        public Booking getBooking() { return booking; }
        public List<BookingItem> getItems() { return items; }
        public String getState() { return BookingRules.effectiveStatus(booking.status(), booking.expiresAt(), now); }
        public long getRemainingSeconds() {
            return "PENDING".equals(getState()) ? Math.max(0, Duration.between(now, booking.expiresAt()).getSeconds()) : 0;
        }
        public boolean isCanNormalize() { return "PENDING".equals(booking.status()) && "EXPIRED".equals(getState()); }
    }

    /** Read-only even when a stored PENDING booking has already expired. */
    public Checkout checkout(long customer, long booking) {
        return database.read(c -> {
            ActorDao.require(c, customer, Role.CUSTOMER);
            Booking owned = bookings.owned(c, customer, booking, false);
            List<BookingItem> items = bookings.items(c, owned.id());
            return new Checkout(owned, items, new EventDao().now(c));
        });
    }

    public void expire(long customer, long booking) {
        database.write(c -> {
            ActorDao.require(c, customer, Role.CUSTOMER);
            Booking owned = bookings.owned(c, customer, booking, true);
            if ("EXPIRED".equals(owned.status())) { return null; }
            if (!"PENDING".equals(owned.status()) || owned.expiresAt().isAfter(new EventDao().now(c))) {
                throw DomainException.conflict("Only an expired pending reservation can be released.");
            }
            bookings.expire(c, customer, owned.id());
            bookings.releaseExpiredInventory(c, owned);
            return null;
        });
    }
}
