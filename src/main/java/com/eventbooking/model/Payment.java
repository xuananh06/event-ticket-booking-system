package com.eventbooking.model;

import com.eventbooking.service.EventTimes;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Owned attempt projection; a refunded success retains its original semantic outcome. */
public record Payment(long id, long bookingId, String key, String fingerprint, BigDecimal amount,
                      String status, LocalDateTime createdAt, LocalDateTime succeededAt, LocalDateTime refundedAt) {
    public String originalOutcome() { return succeededAt == null ? "failure" : "success"; }
    public long getId() { return id; }
    public String getKey() { return key; }
    public String getOriginalOutcome() { return originalOutcome(); }
    public String getStatus() { return status; }
    public String getAmountLabel() { return amount.toPlainString(); }
    public String getCreatedLabel() { return EventTimes.label(createdAt); }
    public String getSucceededLabel() { return succeededAt == null ? "" : EventTimes.label(succeededAt); }
    public String getRefundedLabel() { return refundedAt == null ? "" : EventTimes.label(refundedAt); }
}
