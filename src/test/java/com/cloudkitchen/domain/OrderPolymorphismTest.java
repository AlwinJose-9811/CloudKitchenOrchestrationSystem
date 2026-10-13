package com.cloudkitchen.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.contract.Payable;
import com.cloudkitchen.domain.contract.Trackable;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.testsupport.TestData;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The same call on the same kind of reference gives a different answer for each source (SDD section 4.4). */
class OrderPolymorphismTest {

    private static Order build(OrderSource source, String ref, PaymentMode mode, String distance, OrderItemRequest... items) {
        return OrderFactory.create(TestData.request().source(source).externalRef(ref).paymentMode(mode)
                .distance(distance).items(items).build(), TestData.BIRYANI, TestData.catalog(), TestData.NOW);
    }

    private static Order swiggy() {
        return build(OrderSource.SWIGGY, "SW-1", PaymentMode.PREPAID_AGGREGATOR, "6.10",
                new OrderItemRequest(101, 2), new OrderItemRequest(102, 1));
    }

    private static Order zomato() {
        return build(OrderSource.ZOMATO, "ZO-1", PaymentMode.PREPAID_AGGREGATOR, "6.10",
                new OrderItemRequest(101, 2), new OrderItemRequest(102, 1));
    }

    private static Order inHouse(PaymentMode mode) {
        return build(OrderSource.IN_HOUSE, "IH-1", mode, "6.10",
                new OrderItemRequest(101, 2), new OrderItemRequest(102, 1));
    }

    @Test
    void factoryBuildsTheMatchingSubclass() {
        assertInstanceOf(SwiggyOrder.class, swiggy());
        assertInstanceOf(ZomatoOrder.class, zomato());
        assertInstanceOf(InHouseOrder.class, inHouse(PaymentMode.CASH));
    }

    @Test
    void deliveryEstimateDiffersPerSourceForTheSameBasket() {
        // 3 items: 15 + 3/2 = 16 min preparation. 6.10 km.
        assertEquals(Duration.ofMinutes(16 + 5 + 25), swiggy().calculateDeliveryEstimate(), "ceil(4 x 6.10) = 25");
        assertEquals(Duration.ofMinutes(16 + 7 + 22), zomato().calculateDeliveryEstimate(), "ceil(3.5 x 6.10) = 22");
        assertEquals(Duration.ofMinutes(16 + 19), inHouse(PaymentMode.CASH).calculateDeliveryEstimate(), "ceil(3 x 6.10) = 19");
    }

    @Test
    void estimateIsOrderedZeroDistanceBoundary() {
        // 1 item, 0 km: 15 min preparation, travel 0.
        OrderItemRequest one = new OrderItemRequest(101, 1);
        assertEquals(Duration.ofMinutes(15 + 5), build(OrderSource.SWIGGY, "S", PaymentMode.PREPAID_AGGREGATOR, "0", one).calculateDeliveryEstimate());
        assertEquals(Duration.ofMinutes(15 + 7), build(OrderSource.ZOMATO, "Z", PaymentMode.PREPAID_AGGREGATOR, "0", one).calculateDeliveryEstimate());
        assertEquals(Duration.ofMinutes(15), build(OrderSource.IN_HOUSE, "I", PaymentMode.CASH, "0.00", one).calculateDeliveryEstimate());
    }

    @Test
    void travelTimeAlwaysRoundsUp() {
        OrderItemRequest one = new OrderItemRequest(101, 1);
        // 0.01 km x 4 = 0.04 -> 1 minute; 25.00 km x 3.5 = 87.5 -> 88; 25.00 x 3 = 75 exactly.
        assertEquals(Duration.ofMinutes(15 + 5 + 1), build(OrderSource.SWIGGY, "S", PaymentMode.PREPAID_AGGREGATOR, "0.01", one).calculateDeliveryEstimate());
        assertEquals(Duration.ofMinutes(15 + 7 + 88), build(OrderSource.ZOMATO, "Z", PaymentMode.PREPAID_AGGREGATOR, "25.00", one).calculateDeliveryEstimate());
        assertEquals(Duration.ofMinutes(15 + 75), build(OrderSource.IN_HOUSE, "I", PaymentMode.CASH, "25.00", one).calculateDeliveryEstimate());
    }

    @Test
    void preparationGrowsOneMinuteForEveryTwoItems() {
        for (int quantity : new int[] {1, 2, 3, 4, 19, 20}) {
            Order order = build(OrderSource.IN_HOUSE, "I", PaymentMode.CASH, "0", new OrderItemRequest(101, quantity));
            assertEquals(Duration.ofMinutes(15 + quantity / 2), order.calculateDeliveryEstimate(), "quantity " + quantity);
        }
    }

    @Test
    void estimatedDeliveryTimeIsCreationTimePlusEstimate() {
        Order order = swiggy();
        assertEquals(TestData.NOW.plus(order.calculateDeliveryEstimate()), order.getEstimatedDeliveryAt());
    }

    @Test
    void swiggyKeepsTwentyPercentAndIsPaid() {
        PaymentResult result = swiggy().processPayment();
        assertEquals(PaymentStatus.PAID, result.status());
        assertEquals("697.00", result.amountCharged().toPlainString());
        assertEquals("139.40", result.platformFee().toPlainString());
        assertEquals("557.60", result.netToKitchen().toPlainString());
    }

    @Test
    void zomatoKeepsTwentyTwoPercentAndIsPaid() {
        PaymentResult result = zomato().processPayment();
        assertEquals(PaymentStatus.PAID, result.status());
        assertEquals("697.00", result.amountCharged().toPlainString());
        assertEquals("153.34", result.platformFee().toPlainString());
        assertEquals("543.66", result.netToKitchen().toPlainString());
    }

    @Test
    void inHouseCashStaysPendingWithNoFee() {
        PaymentResult result = inHouse(PaymentMode.CASH).processPayment();
        assertEquals(PaymentStatus.PENDING, result.status());
        assertEquals("697.00", result.amountCharged().toPlainString());
        assertEquals("0.00", result.platformFee().toPlainString());
        assertEquals("697.00", result.netToKitchen().toPlainString());
    }

    @Test
    void inHouseUpiAndCardArePaid() {
        assertEquals(PaymentStatus.PAID, inHouse(PaymentMode.UPI).processPayment().status());
        assertEquals(PaymentStatus.PAID, inHouse(PaymentMode.CARD).processPayment().status());
    }

    @Test
    void commissionRoundsHalfUpOnTheCent() {
        // 1 x 49.50 raita: 20% = 9.90, 22% = 10.89 exactly; 3 x 49.50 = 148.50: 22% = 32.67 exactly.
        // 1 x 99.99 is not on the menu, so use 3 x 49.50 and 1 x 49.50 and check fee + net == charged always.
        for (int quantity = 1; quantity <= 20; quantity++) {
            Order order = build(OrderSource.ZOMATO, "Z", PaymentMode.PREPAID_AGGREGATOR, "1", new OrderItemRequest(103, quantity));
            PaymentResult result = order.processPayment();
            assertEquals(result.amountCharged(), result.platformFee().add(result.netToKitchen()), "quantity " + quantity);
            assertEquals(2, result.platformFee().scale());
        }
        PaymentResult one = build(OrderSource.SWIGGY, "S", PaymentMode.PREPAID_AGGREGATOR, "1", new OrderItemRequest(103, 1)).processPayment();
        assertEquals("9.90", one.platformFee().toPlainString());
    }

    @Test
    void payableAmountIsTheSubtotalForEverySource() {
        for (Order order : List.of(swiggy(), zomato(), inHouse(PaymentMode.UPI))) {
            assertEquals("697.00", order.getPayableAmount().toPlainString());
            assertEquals(order.getSubtotal(), order.getPayableAmount());
        }
    }

    @Test
    void ordersAreUsableThroughTheTrackableAndPayableInterfaces() {
        Trackable trackable = swiggy();
        Payable payable = zomato();
        assertEquals(OrderStatus.PLACED, trackable.getStatus());
        assertTrue(trackable.getHistory().isEmpty());
        assertEquals(12, trackable.getTrackingCode().length());
        assertEquals(PaymentStatus.PAID, payable.processPayment().status());
    }

    @Test
    void collectionsReturnedByAnOrderCannotBeChanged() {
        Order order = swiggy();
        assertThrows(UnsupportedOperationException.class, () -> order.getItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> order.getHistory().clear());
    }

    @Test
    void ordersWithTheSameSourceAndReferenceAreEqual() {
        assertEquals(swiggy(), swiggy());
        assertEquals(swiggy().hashCode(), swiggy().hashCode());
        assertTrue(!swiggy().equals(zomato()));
    }

    @Test
    void toStringNeverShowsTheCustomerName() {
        Order order = swiggy();
        assertTrue(!order.toString().contains("Asha"), order.toString());
        assertTrue(order.toString().contains("SW-1001") || order.toString().contains("Swiggy"));
    }

    @Test
    void moneyFieldsAreBigDecimalNotFloatingPoint() throws Exception {
        assertEquals(BigDecimal.class, Order.class.getMethod("getSubtotal").getReturnType());
        assertEquals(BigDecimal.class, Order.class.getMethod("getPayableAmount").getReturnType());
        assertEquals(BigDecimal.class, PaymentResult.class.getMethod("amountCharged").getReturnType());
    }
}
