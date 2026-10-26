package com.eventbooking.exception;

/**
 * Deliberately does not retain SQL exceptions, URLs, or credentials.
 */
public final class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException() {
        super("Account service unavailable.");
    }
}
