package com.eventbooking.model;

public enum Role {
    CUSTOMER("/customer/home", "Customer"),
    ORGANIZER("/organizer/home", "Organizer"),
    ADMIN("/admin/home", "Administrator");

    private final String homePath;
    private final String displayName;

    Role(String homePath, String displayName) {
        this.homePath = homePath;
        this.displayName = displayName;
    }

    public String getHomePath() {
        return homePath;
    }

    public String getDisplayName() {
        return displayName;
    }
}
