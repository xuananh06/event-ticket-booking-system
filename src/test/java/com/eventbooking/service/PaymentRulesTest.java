package com.eventbooking.service;

import com.eventbooking.dao.BookingDao;
import com.eventbooking.dao.JdbcAccess;
import com.eventbooking.dao.PaymentDao;
import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PaymentRulesTest {
    private static final String KEY = "8d897996-15ee-4fb8-8927-fc69e1f53ef3";

    @Test void keysHaveOneStrictCanonicalAsciiRepresentation() {
        assertEquals(KEY, PaymentRules.key(KEY));
        for (String key : new String[]{null, "", " ", KEY.toUpperCase(), " " + KEY, KEY + " ", KEY + "\t",
                KEY + "\r\n", KEY + "\u0000", KEY.replace('-', '_'), KEY.replace("8", "\uff18"),
                KEY.substring(1), KEY + "0", "a".repeat(100), "a".repeat(101)}) {
            assertThrows(ValidationException.class, () -> PaymentRules.key(key));
        }
    }

    @Test void outcomesAcceptOnlyTheTwoExplicitSimulationActions() {
        assertEquals("success", PaymentRules.outcome("success"));
        assertEquals("failure", PaymentRules.outcome("failure"));
        for (String outcome : new String[]{null, "", "SUCCEEDED", "FAILED", "REFUNDED", "Success", "success ", " failure", "cancel", "success\nfailure"}) {
            assertThrows(ValidationException.class, () -> PaymentRules.outcome(outcome));
        }
    }

    @Test void criticalScalarsRejectMissingAndDuplicateValuesEvenWhenEqual() {
        assertEquals(KEY, PaymentRules.single(new String[]{KEY}, "idempotencyKey"));
        for (String[] values : new String[][]{null, {}, {null}, {KEY, KEY}, {"success", "failure"}}) {
            assertThrows(ValidationException.class, () -> PaymentRules.single(values, "idempotencyKey"));
        }
    }

    @Test void fingerprintIsStableExactAndSensitiveToAcceptedSemantics() {
        String fingerprint = PaymentRules.fingerprint(42, "success", new BigDecimal("1234.50"));
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertEquals(fingerprint, PaymentRules.fingerprint(42, "success", new BigDecimal("1234.5")));
        assertNotEquals(fingerprint, PaymentRules.fingerprint(43, "success", new BigDecimal("1234.50")));
        assertNotEquals(fingerprint, PaymentRules.fingerprint(42, "failure", new BigDecimal("1234.50")));
        assertNotEquals(fingerprint, PaymentRules.fingerprint(42, "success", new BigDecimal("1234.51")));
        assertEquals(PaymentRules.fingerprint(42, "success", BigDecimal.ZERO),
                PaymentRules.fingerprint(42, "success", new BigDecimal("0.00")));
        assertTrue(PaymentRules.fingerprint(42, "success", new BigDecimal("9999999999999999.99")).matches("[0-9a-f]{64}"));
        for (String amount : new String[]{"-0.01", "1.001", "10000000000000000.00"}) {
            assertThrows(DomainException.class, () -> PaymentRules.fingerprint(42, "success", new BigDecimal(amount)));
        }
    }

    @Test void validPaymentWithoutConfigurationFailsGenerically() {
        var service = new PaymentService(new JdbcAccess(Optional.empty()), new BookingDao(), new PaymentDao());
        assertThrows(ServiceUnavailableException.class, () -> service.pay(1, 1, KEY, "success"));
    }
}
