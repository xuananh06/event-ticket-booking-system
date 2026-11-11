package com.eventbooking.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable all-time projections. Aggregate money is not bounded by a single booking. */
public final class StatisticsSummary {
    private StatisticsSummary() { }

    public record Customer(long totalBookings, long pendingBookings, long confirmedBookings,
                           long expiredBookings, long cancelledBookings) {
        public long getTotalBookings() { return totalBookings; }
        public long getPendingBookings() { return pendingBookings; }
        public long getConfirmedBookings() { return confirmedBookings; }
        public long getExpiredBookings() { return expiredBookings; }
        public long getCancelledBookings() { return cancelledBookings; }
    }

    public record Sales(long ticketsSold, BigDecimal grossPayments, BigDecimal refundedPayments) {
        public Sales {
            grossPayments = Objects.requireNonNull(grossPayments).setScale(2, RoundingMode.UNNECESSARY);
            refundedPayments = Objects.requireNonNull(refundedPayments).setScale(2, RoundingMode.UNNECESSARY);
        }
        public BigDecimal netRevenue() { return grossPayments.subtract(refundedPayments); }
        public long getTicketsSold() { return ticketsSold; }
        public String getGrossPaymentsLabel() { return grossPayments.toPlainString(); }
        public String getRefundedPaymentsLabel() { return refundedPayments.toPlainString(); }
        public String getNetRevenueLabel() { return netRevenue().toPlainString(); }
    }

    public record Organizer(long totalEvents, long upcomingPublishedEvents, Sales sales) {
        public long getTotalEvents() { return totalEvents; }
        public long getUpcomingPublishedEvents() { return upcomingPublishedEvents; }
        public Sales getSales() { return sales; }
    }

    public record Admin(long totalAccounts, long totalEvents, long totalBookings, Sales sales) {
        public long getTotalAccounts() { return totalAccounts; }
        public long getTotalEvents() { return totalEvents; }
        public long getTotalBookings() { return totalBookings; }
        public Sales getSales() { return sales; }
    }
}
