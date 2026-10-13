package com.cloudkitchen.testsupport;

import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.service.MenuCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Hand-written fixtures for tests: three active brands, one inactive brand, and a small menu. No database. */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-10T12:00:00.123Z");

    public static final Station BIRYANI = new Station(1, "BIRYANI", "Spice Route Biryani", true);
    public static final Station BURGER = new Station(2, "BURGER", "Burger Barn", true);
    public static final Station PIZZA = new Station(3, "PIZZA", "Pizza Piazza", true);
    public static final Station DESSERT = new Station(4, "DESSERT", "Closed Dessert Bar", false);

    public static final MenuItem CHICKEN_BIRYANI = new MenuItem(101, 1, "Chicken Biryani", new BigDecimal("249.00"), true);
    public static final MenuItem VEG_BIRYANI = new MenuItem(102, 1, "Veg Biryani", new BigDecimal("199.00"), true);
    public static final MenuItem RAITA = new MenuItem(103, 1, "Raita", new BigDecimal("49.50"), true);
    public static final MenuItem OLD_BIRYANI = new MenuItem(104, 1, "Retired Biryani", new BigDecimal("150.00"), false);
    public static final MenuItem CLASSIC_BURGER = new MenuItem(201, 2, "Classic Burger", new BigDecimal("149.00"), true);
    public static final MenuItem MARGHERITA = new MenuItem(301, 3, "Margherita", new BigDecimal("299.00"), true);
    public static final MenuItem COSTLY_PIZZA = new MenuItem(302, 3, "Party Pizza", new BigDecimal("2500.00"), true);
    public static final MenuItem SWEET = new MenuItem(401, 4, "Brownie", new BigDecimal("99.00"), true);

    private TestData() {
    }

    public static MenuCatalog catalog() {
        return MenuCatalog.of(List.of(BIRYANI, BURGER, PIZZA, DESSERT),
                List.of(CHICKEN_BIRYANI, VEG_BIRYANI, RAITA, OLD_BIRYANI, CLASSIC_BURGER, MARGHERITA, COSTLY_PIZZA, SWEET));
    }

    /** A valid Swiggy request: 2 x Chicken Biryani + 1 x Veg Biryani (subtotal 697.00), 6.10 km. */
    public static RequestBuilder request() {
        return new RequestBuilder();
    }

    /** Mutable builder so tests can break exactly one rule at a time. */
    public static final class RequestBuilder {
        private OrderSource source = OrderSource.SWIGGY;
        private String externalRef = "SW-1001";
        private String brandCode = "BIRYANI";
        private String customerName = "Asha Rao";
        private BigDecimal distance = new BigDecimal("6.10");
        private PaymentMode paymentMode = PaymentMode.PREPAID_AGGREGATOR;
        private List<OrderItemRequest> items = new ArrayList<>(List.of(new OrderItemRequest(101, 2), new OrderItemRequest(102, 1)));

        public RequestBuilder source(OrderSource value) {
            this.source = value;
            return this;
        }

        public RequestBuilder externalRef(String value) {
            this.externalRef = value;
            return this;
        }

        public RequestBuilder brandCode(String value) {
            this.brandCode = value;
            return this;
        }

        public RequestBuilder customerName(String value) {
            this.customerName = value;
            return this;
        }

        public RequestBuilder distance(String value) {
            this.distance = value == null ? null : new BigDecimal(value);
            return this;
        }

        public RequestBuilder paymentMode(PaymentMode value) {
            this.paymentMode = value;
            return this;
        }

        public RequestBuilder items(List<OrderItemRequest> value) {
            this.items = value == null ? null : new ArrayList<>(value);
            return this;
        }

        public RequestBuilder items(OrderItemRequest... value) {
            this.items = new ArrayList<>(List.of(value));
            return this;
        }

        /** Turns the request into a valid in-house cash order with a blank reference. */
        public RequestBuilder inHouse() {
            return source(OrderSource.IN_HOUSE).externalRef("").paymentMode(PaymentMode.CASH);
        }

        public OrderRequest build() {
            return new OrderRequest(source, externalRef, brandCode, customerName, distance, paymentMode, items);
        }
    }
}
