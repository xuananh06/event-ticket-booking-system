package com.eventbooking.security;

import java.io.Serializable;

/**
 * The only authenticated account state retained in a session.
 */
public record SessionIdentity(long userId) implements Serializable {
    public static final String ATTRIBUTE = "authenticatedIdentity";
}
