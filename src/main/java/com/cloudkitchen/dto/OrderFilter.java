package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import java.time.Instant;

/** Optional search criteria for the order list; a {@code null} field means "any". */
public record OrderFilter(OrderStatus status, OrderSource source, String externalRefContains, Instant fromUtc,
                          Instant toUtc) {

    public static final int MAX_TEXT_LENGTH = 64;

    public OrderFilter {
        externalRefContains = externalRefContains == null || externalRefContains.isBlank()
                ? null : externalRefContains.strip();
        if (externalRefContains != null && externalRefContains.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("externalRefContains is at most " + MAX_TEXT_LENGTH + " characters");
        }
        if (fromUtc != null && toUtc != null && fromUtc.isAfter(toUtc)) {
            throw new IllegalArgumentException("fromUtc must not be after toUtc");
        }
    }

    /** A filter that matches every order. */
    public static OrderFilter any() {
        return new OrderFilter(null, null, null, null, null);
    }
}
