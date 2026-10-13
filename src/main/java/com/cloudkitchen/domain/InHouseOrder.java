package com.cloudkitchen.domain;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.util.MoneyUtil;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** An order taken by the restaurant itself: own riders (no hand-off), no platform fee, cash settles later. */
public final class InHouseOrder extends Order {

    private static final BigDecimal MINUTES_PER_KM = new BigDecimal("3");

    InHouseOrder(Long id, String externalRef, String trackingCode, long stationId, String brandCode,
                 OrderStatus status, String customerName, BigDecimal deliveryDistanceKm, PaymentMode paymentMode,
                 List<OrderItem> items, List<StatusHistoryEntry> history, Instant createdAt) {
        super(id, OrderSource.IN_HOUSE, externalRef, trackingCode, stationId, brandCode, status, customerName,
                deliveryDistanceKm, paymentMode, items, history, createdAt);
    }

    InHouseOrder(String externalRef, String trackingCode, long stationId, String brandCode, String customerName,
                 BigDecimal deliveryDistanceKm, PaymentMode paymentMode, List<OrderItem> items, Instant createdAt) {
        super(OrderSource.IN_HOUSE, externalRef, trackingCode, stationId, brandCode, customerName, deliveryDistanceKm,
                paymentMode, items, createdAt);
    }

    /** Preparation + 3 minutes per km (rounded up); the restaurant's own riders need no hand-off. */
    @Override
    public Duration calculateDeliveryEstimate() {
        return basePreparation().plus(travelTime(MINUTES_PER_KM));
    }

    /**
     * No platform fee. The full subtotal is charged; cash is still {@code PENDING} (collected on delivery), UPI and
     * card are {@code PAID}.
     */
    @Override
    public PaymentResult processPayment() {
        BigDecimal subtotal = getSubtotal();
        PaymentStatus status = getPaymentMode() == PaymentMode.CASH ? PaymentStatus.PENDING : PaymentStatus.PAID;
        return new PaymentResult(status, subtotal, MoneyUtil.ZERO, subtotal);
    }

    @Override
    public String toString() {
        return describe(getSource().label() + " order");
    }
}
