package com.eventbooking.service;

import com.eventbooking.dao.EventDao;
import com.eventbooking.dao.InventoryDao;
import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.model.Event;
import com.eventbooking.model.InventorySeat;
import com.eventbooking.model.InventorySummary;
import com.eventbooking.model.Page;

public final class PublicInventoryService {
    private final JdbcAccess database;
    private final EventDao events;
    private final InventoryDao inventory;

    public PublicInventoryService(JdbcAccess database, EventDao events, InventoryDao inventory) {
        this.database = database;
        this.events = events;
        this.inventory = inventory;
    }

    public record View(Event event, Page<InventorySeat> page, InventorySummary summary) {
    }

    public View view(long eventId, int page, int size) {
        return database.read(c -> {
            var now = events.now(c);
            Event event = events.published(c, eventId, now);
            // Each inventory query independently applies the exact public SQL predicate and captured time.
            return new View(event, inventory.page(c, eventId, now, page, size, true), inventory.summary(c, eventId, now, true));
        });
    }
}
