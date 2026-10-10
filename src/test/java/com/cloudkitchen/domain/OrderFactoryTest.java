package com.cloudkitchen.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OrderFactoryTest {

    private static Order create(OrderRequest request) {
        return OrderFactory.create(request, TestData.BIRYANI, TestData.catalog(), TestData.NOW);
    }

    @Test
    void createsAPlacedOrderPricedFromTheCatalogue() {
        Order order = create(TestData.request().build());
        assertEquals(OrderStatus.PLACED, order.getStatus());
        assertNull(order.getId(), "the database assigns the id");
        assertTrue(order.getHistory().isEmpty());
        assertEquals(1, order.getStationId());
        assertEquals("BIRYANI", order.getBrandCode());
        assertEquals("SW-1001", order.getExternalRef());
        assertEquals(2, order.getItems().size());
        assertEquals("Chicken Biryani", order.getItems().get(0).getItemName());
        assertEquals("249.00", order.getItems().get(0).getUnitPrice().toPlainString());
        assertEquals("697.00", order.getSubtotal().toPlainString());
        assertEquals(3, order.totalQuantity());
    }

    @Test
    void clientCannotChooseThePrice() {
        // OrderItemRequest carries only an id and a quantity; the price always comes from MenuCatalog.
        assertEquals(2, OrderItemRequest.class.getRecordComponents().length);
    }

    @Test
    void creationTimeIsTruncatedToMilliseconds() {
        Order order = OrderFactory.create(TestData.request().build(), TestData.BIRYANI, TestData.catalog(),
                Instant.parse("2026-10-10T12:00:00.123456789Z"));
        assertEquals(Instant.parse("2026-10-10T12:00:00.123Z"), order.getCreatedAt());
    }

    @Test
    void trackingCodesAreTwelveUppercaseAlphanumericAndDiffer() {
        Pattern shape = Pattern.compile("[A-Z0-9]{12}");
        String previous = "";
        for (int i = 0; i < 50; i++) {
            String code = create(TestData.request().build()).getTrackingCode();
            assertTrue(shape.matcher(code).matches(), code);
            assertNotEquals(previous, code);
            previous = code;
        }
    }

    @Test
    void theCodeSupplierOverloadIsDeterministic() {
        Order order = OrderFactory.create(TestData.request().build(), TestData.BIRYANI, TestData.catalog(),
                TestData.NOW, () -> "ABCDEFGHJK12");
        assertEquals("ABCDEFGHJK12", order.getTrackingCode());
    }

    @Test
    void aBadTrackingCodeFromTheSupplierIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OrderFactory.create(TestData.request().build(),
                TestData.BIRYANI, TestData.catalog(), TestData.NOW, () -> "short"));
        assertThrows(IllegalArgumentException.class, () -> OrderFactory.create(TestData.request().build(),
                TestData.BIRYANI, TestData.catalog(), TestData.NOW, () -> "lowercase123"));
    }

    @Test
    void inHouseOrderWithoutReferenceGetsAGeneratedOne() {
        AtomicInteger calls = new AtomicInteger();
        Order order = OrderFactory.create(TestData.request().inHouse().build(), TestData.BIRYANI, TestData.catalog(),
                TestData.NOW, () -> calls.getAndIncrement() == 0 ? "TRACKCODE001" : "REFERENCE002");
        assertEquals("TRACKCODE001", order.getTrackingCode());
        assertEquals("IH-REFERENCE0", order.getExternalRef());
        assertEquals(OrderSource.IN_HOUSE, order.getSource());
    }

    @Test
    void anAggregatorOrderWithoutReferenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().externalRef("").build()));
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().externalRef(null).build()));
    }

    @Test
    void stationMustBeActiveAndMatchTheBrand() {
        OrderRequest dessert = TestData.request().brandCode("DESSERT").items(new OrderItemRequest(401, 1)).build();
        assertThrows(IllegalArgumentException.class,
                () -> OrderFactory.create(dessert, TestData.DESSERT, TestData.catalog(), TestData.NOW));
        assertThrows(IllegalArgumentException.class,
                () -> OrderFactory.create(TestData.request().build(), TestData.PIZZA, TestData.catalog(), TestData.NOW));
    }

    @Test
    void unknownInactiveOrForeignItemsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().items(new OrderItemRequest(999, 1)).build()));
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().items(new OrderItemRequest(104, 1)).build()), "inactive");
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().items(new OrderItemRequest(201, 1)).build()), "other brand");
    }

    @Test
    void sourceAndPaymentModeMustBeConsistent() {
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().paymentMode(PaymentMode.CASH).build()), "Swiggy is prepaid");
        assertThrows(IllegalArgumentException.class,
                () -> create(TestData.request().inHouse().paymentMode(PaymentMode.PREPAID_AGGREGATOR).build()), "in-house is not prepaid");
    }

    @Test
    void distanceIsNormalisedToTwoDecimalsAndBoundedBelowByZero() {
        assertEquals("6.10", create(TestData.request().distance("6.1").build()).getDeliveryDistanceKm().toPlainString());
        assertEquals("0.00", create(TestData.request().distance("0").build()).getDeliveryDistanceKm().toPlainString());
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().distance("-0.01").build()));
    }

    @Test
    void itemCountBoundaries() {
        List<OrderItemRequest> none = List.of();
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().items(none).build()));
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().items(new OrderItemRequest(101, 0)).build()));
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request().items(new OrderItemRequest(101, 21)).build()));
        assertEquals(20, create(TestData.request().items(new OrderItemRequest(101, 20)).build()).totalQuantity());
        assertEquals(1, create(TestData.request().items(new OrderItemRequest(101, 1)).build()).totalQuantity());
        assertThrows(IllegalArgumentException.class, () -> create(TestData.request()
                .items(new OrderItemRequest(101, 1), new OrderItemRequest(101, 1)).build()), "duplicate line");
    }

    @Test
    void rehydrateRestoresIdStatusAndHistory() {
        Order fresh = create(TestData.request().build());
        Order loaded = OrderFactory.rehydrate(42L, fresh.getSource(), fresh.getExternalRef(), fresh.getTrackingCode(),
                fresh.getStationId(), fresh.getBrandCode(), OrderStatus.PLACED, fresh.getCustomerName(),
                fresh.getDeliveryDistanceKm(), fresh.getPaymentMode(), fresh.getItems(), List.of(), fresh.getCreatedAt());
        assertEquals(42L, loaded.getId());
        assertEquals(OrderStatus.PLACED, loaded.getStatus());
        assertEquals(fresh, loaded);
        assertEquals(fresh.getSubtotal(), loaded.getSubtotal());
    }

    @Test
    void rehydrateAcceptsAConsistentHistoryAndRejectsOneThatDisagreesWithTheStatus() {
        Order fresh = create(TestData.request().build());
        List<StatusHistoryEntry> placedThenValidated = List.of(
                new StatusHistoryEntry(42L, null, OrderStatus.PLACED, TestData.NOW, "system", null),
                new StatusHistoryEntry(42L, OrderStatus.PLACED, OrderStatus.VALIDATED, TestData.NOW, "system", null));
        Order loaded = rehydrate(fresh, OrderStatus.VALIDATED, placedThenValidated);
        assertEquals(OrderStatus.VALIDATED, loaded.getStatus());
        assertEquals(2, loaded.getHistory().size());
        assertThrows(IllegalArgumentException.class, () -> rehydrate(fresh, OrderStatus.ROUTED, placedThenValidated),
                "history ends in VALIDATED, status says ROUTED");
        assertThrows(IllegalArgumentException.class, () -> rehydrate(fresh, OrderStatus.VALIDATED,
                placedThenValidated.subList(1, 2)), "chain must start from the creation entry");
    }

    private static Order rehydrate(Order fresh, OrderStatus status, List<StatusHistoryEntry> history) {
        return OrderFactory.rehydrate(42L, fresh.getSource(), fresh.getExternalRef(), fresh.getTrackingCode(),
                fresh.getStationId(), fresh.getBrandCode(), status, fresh.getCustomerName(),
                fresh.getDeliveryDistanceKm(), fresh.getPaymentMode(), fresh.getItems(), history, fresh.getCreatedAt());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> OrderFactory.create(null, TestData.BIRYANI, TestData.catalog(), TestData.NOW));
        assertThrows(NullPointerException.class, () -> OrderFactory.create(TestData.request().build(), null, TestData.catalog(), TestData.NOW));
        assertThrows(NullPointerException.class, () -> OrderFactory.create(TestData.request().build(), TestData.BIRYANI, null, TestData.NOW));
        assertThrows(NullPointerException.class, () -> OrderFactory.create(TestData.request().build(), TestData.BIRYANI, TestData.catalog(), null));
    }
}
