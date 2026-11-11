package com.eventbooking.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsSummaryTest {
    @Test void refundedSuccessRemainsInGrossAndNetIsSubtractedExactlyOnce() {
        var sales = new StatisticsSummary.Sales(2, new BigDecimal("600000"), new BigDecimal("300000"));
        assertEquals(new BigDecimal("300000.00"), sales.netRevenue());
        assertEquals("600000.00", sales.getGrossPaymentsLabel());
        assertEquals("300000.00", sales.getRefundedPaymentsLabel());
        assertEquals("300000.00", sales.getNetRevenueLabel());
    }

    @Test void aggregateValuesExceedSingleBookingAndIntegerBoundsWithoutRounding() {
        var sales = new StatisticsSummary.Sales((long) Integer.MAX_VALUE + 1,
                new BigDecimal("19999999999999999.98"), BigDecimal.ZERO);
        assertEquals(2147483648L, sales.getTicketsSold());
        assertEquals("19999999999999999.98", sales.getNetRevenueLabel());
        assertEquals("0.00", sales.getRefundedPaymentsLabel());
    }
}
