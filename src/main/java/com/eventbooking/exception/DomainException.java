package com.eventbooking.exception;

/** Controlled business failure, without database diagnostics. */
public final class DomainException extends RuntimeException {
    private final int status;
    public DomainException(int status, String message) { super(message); this.status = status; }
    public int getStatus() { return status; }
    public static DomainException missing() { return new DomainException(404, "This record was not found."); }
    public static DomainException forbidden() { return new DomainException(403, "You do not have permission to perform this action."); }
    public static DomainException conflict(String message) { return new DomainException(409, message); }
}
