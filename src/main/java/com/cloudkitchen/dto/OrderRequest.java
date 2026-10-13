package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a source sends for one order (SRS 2.6). Fields may be {@code null} or invalid: this record only carries
 * them, and {@code OrderValidator} decides. Strings are trimmed on construction and the item list is copied, so the
 * request cannot change after it was validated.
 */
public record OrderRequest(OrderSource source, String externalRef, String brandCode, String customerName,
                           BigDecimal deliveryDistanceKm, PaymentMode paymentMode, List<OrderItemRequest> items) {

    public OrderRequest {
        externalRef = strip(externalRef);
        brandCode = strip(brandCode);
        customerName = strip(customerName);
        items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
