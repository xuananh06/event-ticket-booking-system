package com.eventbooking.model;

public record Seat(long id, long venueId, String venueName, String rowLabel, int seatNumber, boolean active) {
    public long getId() { return id; }
    public long getVenueId() { return venueId; }
    public String getVenueName() { return venueName; }
    public String getRowLabel() { return rowLabel; }
    public int getSeatNumber() { return seatNumber; }
    public boolean isActive() { return active; }
}
