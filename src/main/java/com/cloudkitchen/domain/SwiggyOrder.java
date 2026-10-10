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

/** An order that arrived through Swiggy: prepaid, 20 percent commission, 5 minutes of hand-off to the rider. */
public final class SwiggyOrder extends Order {

    static final int COMMISSION_PERCENT = 20;
    private static final Duration HAND_OFF = Duration.ofMinutes(5);
    private static final BigDecimal MINUTES_PER_KM = new BigDecimal("4");

    SwiggyOrder(Long id, String externalRef, String trackingCode, long stationId, String brandCode,
                OrderStatus status, String customerName, BigDecimal deliveryDistanceKm, PaymentMode paymentMode,
                List<OrderItem> items, List<StatusHistoryEntry> history, Instant createdAt) {
        super(id, OrderSource.SWIGGY, externalRef, trackingCode, stationId, brandCode, status, customerName,
                deliveryDistanceKm, paymentMode, items, history, createdAt);
    }

    SwiggyOrder(String externalRef, String trackingCode, long stationId, String brandCode, String customerName,
                BigDecimal deliveryDistanceKm, PaymentMode paymentMode, List<OrderItem> items, Instant createdAt) {
        super(OrderSource.SWIGGY, externalRef, trackingCode, stationId, brandCode, customerName, deliveryDistanceKm,
                paymentMode, items, createdAt);
    }

    /** Preparation + 5 minutes hand-off + 4 minutes per km (rounded up). */
    @Override
    public Duration calculateDeliveryEstimate() {
        return basePreparation().plus(HAND_OFF).plus(travelTime(MINUTES_PER_KM));
    }

    /** Always paid up front; the platform keeps 20 percent of the subtotal. */
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
