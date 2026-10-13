package com.cloudkitchen.domain.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class EnumsTest {

    @Test
    void sourceLabels() {
        assertEquals("Swiggy", OrderSource.SWIGGY.label());
        assertEquals("Zomato", OrderSource.ZOMATO.label());
        assertEquals("In-house", OrderSource.IN_HOUSE.label());
        assertEquals(3, OrderSource.values().length);
    }

    @Test
    void paymentModeIsAllowedPerSourceExactlyAsInTheSrs() {
        for (OrderSource source : new OrderSource[] {OrderSource.SWIGGY, OrderSource.ZOMATO}) {
            for (PaymentMode mode : PaymentMode.values()) {
                assertEquals(mode == PaymentMode.PREPAID_AGGREGATOR, mode.allowedFor(source), mode + " for " + source);
            }
        }
        for (PaymentMode mode : PaymentMode.values()) {
            assertEquals(mode != PaymentMode.PREPAID_AGGREGATOR, mode.allowedFor(OrderSource.IN_HOUSE), mode + " for IN_HOUSE");
        }
    }

    @Test
    void aNullSourceIsNeverAllowed() {
        for (PaymentMode mode : PaymentMode.values()) {
            assertFalse(mode.allowedFor(null));
        }
    }

    @Test
    void everyPaymentModeIsAllowedForAtLeastOneSource() {
        for (PaymentMode mode : PaymentMode.values()) {
            assertTrue(EnumSet.allOf(OrderSource.class).stream().anyMatch(mode::allowedFor), mode.name());
        }
    }

    @Test
    void constantsOfTheOtherEnumsAreExactlyThoseOfTheDesign() {
        assertEquals(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.PAID, PaymentStatus.FAILED), EnumSet.allOf(PaymentStatus.class));
        assertEquals(EnumSet.of(Role.ADMIN, Role.KITCHEN_STAFF), EnumSet.allOf(Role.class));
        assertEquals(7, WorkerState.values().length);
        assertEquals(WorkerState.STARTING, WorkerState.valueOf("STARTING"));
        assertEquals(WorkerState.DEAD, WorkerState.valueOf("DEAD"));
    }
}
