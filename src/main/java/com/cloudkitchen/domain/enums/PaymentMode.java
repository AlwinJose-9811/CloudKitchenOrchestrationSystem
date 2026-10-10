package com.cloudkitchen.domain.enums;

/** How the customer pays. Aggregators prepay; in-house orders pay at the counter, by UPI or by card. */
public enum PaymentMode {
    PREPAID_AGGREGATOR, CASH, UPI, CARD;

    /** Whether this payment mode may be used with the given order source (VR-06). */
    public boolean allowedFor(OrderSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case SWIGGY, ZOMATO -> this == PREPAID_AGGREGATOR;
            case IN_HOUSE -> this != PREPAID_AGGREGATOR;
        };
    }
}
