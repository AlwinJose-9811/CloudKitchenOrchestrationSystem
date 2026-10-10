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

/** An order that arrived through Zomato: prepaid, 22 percent commission, 7 minutes of hand-off to the rider. */
public final class ZomatoOrder extends Order {

    static final int COMMISSION_PERCENT = 22;
    private static final Duration HAND_OFF = Duration.ofMinutes(7);
    private static final BigDecimal MINUTES_PER_KM = new BigDecimal("3.5");

    ZomatoOrder(Long id, String externalRef, String trackingCode, long stationId, String brandCode,
                OrderStatus status, String customerName, BigDecimal deliveryDistanceKm, PaymentMode paymentMode,
                List<OrderItem> items, List<StatusHistoryEntry> history, Instant createdAt) {
        super(id, OrderSource.ZOMATO, externalRef, trackingCode, stationId, brandCode, status, customerName,
                deliveryDistanceKm, paymentMode, items, history, createdAt);
    }

    ZomatoOrder(String externalRef, String trackingCode, long stationId, String brandCode, String customerName,
                BigDecimal deliveryDistanceKm, PaymentMode paymentMode, List<OrderItem> items, Instant createdAt) {
        super(OrderSource.ZOMATO, externalRef, trackingCode, stationId, brandCode, customerName, deliveryDistanceKm,
                paymentMode, items, createdAt);
    }

    /** Preparation + 7 minutes hand-off + 3.5 minutes per km (rounded up). */
    @Override
    public Duration calculateDeliveryEstimate() {
        return basePreparation().plus(HAND_OFF).plus(travelTime(MINUTES_PER_KM));
    }

    /** Always paid up front; the platform keeps 22 percent of the subtotal. */
    @Override
    public PaymentResult processPayment() {
        BigDecimal subtotal = getSubtotal();
        BigDecimal fee = MoneyUtil.percentOf(subtotal, COMMISSION_PERCENT);
        return new PaymentResult(PaymentStatus.PAID, subtotal, fee, MoneyUtil.subtract(subtotal, fee));
    }

    @Override
    public String toString() {
        return describe(getSource().label() + " order");
    }
}
