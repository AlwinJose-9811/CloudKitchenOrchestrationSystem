package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.domain.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** One line of the daily CSV report (SDD 8.2). Deliberately has no customer data. */
public record ReportRow(long orderId, Instant createdAtUtc, OrderSource source, String brand, String externalRef,
                        OrderStatus status, int itemCount, BigDecimal subtotal, BigDecimal platformFee,
                        BigDecimal total, PaymentMode paymentMode, PaymentStatus paymentStatus) {
}
