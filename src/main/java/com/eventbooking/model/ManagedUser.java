package com.eventbooking.model;

/**
 * Administrative projection; never selects or holds a password hash.
 */
public record ManagedUser(long id, String fullName, String email, Role role, boolean active) {
    public long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "ManagedUser[id=" + id + ", role=" + role + "]";
    }
}
