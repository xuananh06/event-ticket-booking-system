package com.eventbooking.model;

import com.eventbooking.service.EventTimes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Owned checkout projection; never used as an allocation decision. */
public record Booking(long id, String code, long eventId, String eventTitle, String status,
                      BigDecimal total, LocalDateTime createdAt, LocalDateTime expiresAt) {
    public long getId() { return id; }
    public String getCode() { return code; }
    public long getEventId() { return eventId; }
    public String getEventTitle() { return eventTitle; }
    public String getStatus() { return status; }
    public String getTotalLabel() { return total.toPlainString(); }
    public String getExpiresLabel() { return EventTimes.label(expiresAt); }
    public String getExpiresUtc() { return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(expiresAt) + "Z"; }
}
