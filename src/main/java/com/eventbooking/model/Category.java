package com.eventbooking.model;

public record Category(long id, String name, boolean active) {
    public long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
