package com.eventbooking.model;

public record Venue(long id, String name, String address, boolean active) {
    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public boolean isActive() {
        return active;
    }
}
