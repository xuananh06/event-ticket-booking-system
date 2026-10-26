package com.eventbooking.security;

import com.eventbooking.model.Role;

import java.util.Optional;

public final class SecurityPaths {
    private SecurityPaths() {
    }

    public static Optional<Role> requiredRole(String path) {
        for (Role role : Role.values()) {
            String root = "/" + role.name().toLowerCase(java.util.Locale.ROOT);
            if (path.equals(root) || path.startsWith(root + "/")) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    public static boolean authAction(String path) {
        return path.equals("/register") || path.equals("/login") || path.equals("/logout");
    }
}
