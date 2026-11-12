package com.eventbooking.exception;

public final class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("The sign-in details are incorrect.");
    }
}
