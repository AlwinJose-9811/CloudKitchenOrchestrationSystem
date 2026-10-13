package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** One row of the order list. It carries no customer data. */
public record OrderSummary(long id, OrderSource source, String brandCode, String externalRef, OrderStatus status,
                           BigDecimal total, Instant createdAt) {
}
