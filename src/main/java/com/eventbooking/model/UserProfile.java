package com.eventbooking.model;

/** Safe request/view model. Never contains credentials. */
public final class UserProfile {
    private final long id;
    private final String email;
    private final String fullName;
    private final Role role;

    public UserProfile(long id, String email, String fullName, Role role) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.role = java.util.Objects.requireNonNull(role);
    }

    public long getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }

    @Override
    public String toString() { return "UserProfile[id=" + id + ", role=" + role + "]"; }
}
