package com.eventbooking.model;

/**
 * DAO/service-only account data, never attached to an HTTP request or session.
 */
public final class UserAccount {
    private final UserProfile profile;
    private final boolean active;
    private final String passwordHash;

    public UserAccount(UserProfile profile, boolean active, String passwordHash) {
        this.profile = java.util.Objects.requireNonNull(profile);
        this.active = active;
        this.passwordHash = passwordHash;
    }

    public UserProfile getProfile() {
        return profile;
    }

    public boolean isActive() {
        return active;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    @Override
    public String toString() {
        return "UserAccount[redacted]";
    }
}
