package com.eventbooking.service;

import com.eventbooking.dao.ActorDao;
import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.dao.ReferenceDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Event;
import com.eventbooking.model.InventorySeat;
import com.eventbooking.model.InventorySummary;
import com.eventbooking.model.Page;
import com.eventbooking.model.Role;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class InventoryService {
    private final JdbcAccess database;
    private final EventDao events;
    private final InventoryDao inventory;

    public InventoryService(JdbcAccess database, EventDao events, InventoryDao inventory) {
        this.database = database;
        this.events = events;
        this.inventory = inventory;
    }

    public record View(Event event, Page<InventorySeat> page, InventorySummary summary, List<String> editReasons,
                       List<String> publicationReasons) {
        public View {
            editReasons = List.copyOf(editReasons);
            publicationReasons = List.copyOf(publicationReasons);
        }

        public boolean editable() {
            return editReasons.isEmpty();
        }

        public boolean ready() {
            return publicationReasons.isEmpty();
        }
    }

    public View view(long actor, long eventId, int page, int size) {
        return database.read(c -> {
            Event event = owned(c, actor, eventId);
            InventoryDao.Checks checks = inventory.checks(c, event);
            List<String> reasons = editReasons(c, event, checks);
            var now = events.now(c);
            return new View(event, inventory.page(c, eventId, now, page, size), inventory.summary(c, eventId, now), reasons, publicationReasons(reasons, checks));
        });
    }

    public void publish(long actor, long eventId) {
        database.write(c -> {
            Event event = owned(c, actor, eventId);
            InventoryDao.Checks checks = inventory.checks(c, event);
            List<String> reasons = publicationReasons(editReasons(c, event, checks), checks);
            if (!reasons.isEmpty()) {
                throw DomainException.conflict(reasons.get(0));
            }
            // Rechecks database time at the guarded update after all authoritative locks.
            inventory.publish(c, event);
            return null;
        });
    }

    private List<String> publicationReasons(List<String> editing, InventoryDao.Checks checks) {
        List<String> reasons = new ArrayList<>(editing);
        if (checks.inventory() == 0) {
            reasons.add("Generate nonempty inventory before publishing.");
        }
        if (checks.missingSeats() > 0 || checks.inventory() != checks.activeSeats()) {
            reasons.add("Inventory must cover every currently active venue seat exactly once. Generate missing seats before publishing.");
        }
        // DECIMAL(18,2), NOT NULL, nonnegative CHECK and unique/composite FKs remain authoritative.
        return reasons;
    }

    public int generate(long actor, long eventId, String value) {
        BigDecimal price = InventoryInputs.price(value);
        return database.write(c -> {
            Event event = owned(c, actor, eventId);
            InventoryDao.Checks checks = inventory.checks(c, event);
            requireEditable(c, event, checks);
            int added = inventory.generate(c, event, price);
            if (added != checks.missingSeats()) {
                throw DomainException.conflict("The event changed or has already started. No inventory was added.");
            }
            return added;
        });
    }

    public void price(long actor, long eventId, String[] values, String value) {
        BigDecimal price = InventoryInputs.price(value);
        List<Long> ids = InventoryInputs.selection(values);
        database.write(c -> {
            Event event = owned(c, actor, eventId);
            InventoryDao.Checks checks = inventory.checks(c, event);
            requireEditable(c, event, checks);
            inventory.requireMembers(c, eventId, ids);
            inventory.price(c, event, ids, price);
            return null;
        });
    }

    private Event owned(Connection c, long actor, long eventId) throws SQLException {
        ActorDao.require(c, actor, Role.ORGANIZER);
        return events.owned(c, actor, eventId);
    }

    private void requireEditable(Connection c, Event event, InventoryDao.Checks checks) throws SQLException {
        List<String> reasons = editReasons(c, event, checks);
        if (!reasons.isEmpty()) {
            throw DomainException.conflict(reasons.get(0));
        }
    }

    private List<String> editReasons(Connection c, Event event, InventoryDao.Checks checks) throws SQLException {
        List<String> reasons = new ArrayList<>();
        if (!event.isDraft()) {
            reasons.add("Only draft event inventory can be changed.");
        }
        ReferenceDao references = new ReferenceDao();
        if (!references.category(c, event.categoryId()).active() || !references.venue(c, event.venueId()).active()) {
            reasons.add("The event category and venue must be active.");
        }
        if (event.title().isBlank() || event.title().length() > 200
                || event.title().codePoints().anyMatch(Character::isISOControl) || event.description().isBlank()
                || event.description().length() > 10000
                || event.description().codePoints().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
                || !event.endsAt().isAfter(event.startsAt())) {
            reasons.add("Correct the event details and time range before setting up inventory.");
        }
        if (checks.activeSeats() == 0) {
            reasons.add("The venue has no active physical seats.");
        }
        if (checks.invalidMembers() > 0) {
            reasons.add("Inventory contains an inactive or mismatched physical seat.");
        }
        if (checks.nonPristine() > 0 || checks.history() > 0) {
            reasons.add("Inventory with booking history, holds or sales cannot be changed.");
        }
        // Capture time after acquiring the authoritative reference/inventory/history locks.
        if (!event.startsAt().isAfter(events.now(c))) {
            reasons.add("The event must start in the future.");
        }
        return reasons;
    }
}
