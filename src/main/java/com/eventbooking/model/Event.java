package com.eventbooking.model;

import com.eventbooking.service.EventTimes;

import java.time.LocalDateTime;

public record Event(long id, long organizerId, long categoryId, long venueId, String title, String description,
                    LocalDateTime startsAt, LocalDateTime endsAt, String status, String categoryName,
                    String venueName) {
    public long getId() {
        return id;
    }

    public long getOrganizerId() {
        return organizerId;
    }

    public long getCategoryId() {
        return categoryId;
    }

    public long getVenueId() {
        return venueId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public String getVenueName() {
        return venueName;
    }

    public String getStartsInput() {
        return EventTimes.input(startsAt);
    }

    public String getEndsInput() {
        return EventTimes.input(endsAt);
    }

    public String getStartsLabel() {
        return EventTimes.label(startsAt);
    }

    public String getEndsLabel() {
        return EventTimes.label(endsAt);
    }

    public boolean isDraft() {
        return "DRAFT".equals(status);
    }
}
