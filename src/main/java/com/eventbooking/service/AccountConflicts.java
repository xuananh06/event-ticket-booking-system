package com.eventbooking.service;

import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;

import java.sql.SQLException;
import java.util.*;

/** Maps authoritative account uniqueness failures without exposing SQL messages or causes. */
final class AccountConflicts {
    private AccountConflicts() { }

    static RuntimeException failure(SQLException error) {
        Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException current = error; current != null && seen.add(current); current = current.getNextException()) {
            if (current.getErrorCode() != 2601 && current.getErrorCode() != 2627) { continue; }
            String message = current.getMessage();
            if (message == null) { continue; }
            // Only fixed object names are inspected; no message content is returned or logged.
            if (message.contains("'UX_users_username'")) {
                return new ValidationException(Map.of("username", "This username is already registered."));
            }
            if (message.contains("'UQ_users_email'")) {
                return new ValidationException(Map.of("email", "This email address is already registered."));
            }
        }
        return new ServiceUnavailableException();
    }
}
