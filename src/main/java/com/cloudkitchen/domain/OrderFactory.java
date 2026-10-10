package com.cloudkitchen.domain;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.service.MenuCatalog;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The only way to build an {@link Order}. It picks the subclass from the source, takes names and prices from the
 * catalogue (never from the request, so a tampered price has no effect), and generates the tracking code.
 *
 * <p>The request must already have passed {@code OrderValidator}; the factory still refuses what it cannot build
 * consistently and reports that with {@link IllegalArgumentException}, which signals a programming error.
 */
public final class OrderFactory {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TRACKING_CODE_LENGTH = 12;
    private static final int GENERATED_REF_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrderFactory() {
    }

    /** Creates a new order in {@code PLACED} with an unguessable tracking code from a {@link SecureRandom}. */
    public static Order create(OrderRequest request, Station station, MenuCatalog catalog, Instant now) {
        return create(request, station, catalog, now, OrderFactory::randomCode);
    }

    /**
     * Same as {@link #create(OrderRequest, Station, MenuCatalog, Instant)} but with the source of random codes
     * supplied, which makes the result predictable in tests. Each call to {@code codes} must return 12 characters
     * from A-Z0-9; for an in-house order without a reference one more code is drawn to generate it.
     */
    public static Order create(OrderRequest request, Station station, MenuCatalog catalog, Instant now,
                               Supplier<String> codes) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(codes, "codes");
        if (!station.isActive() || !station.getCode().equals(request.brandCode())) {
            throw new IllegalArgumentException("station " + station.getCode() + " cannot serve brand " + request.brandCode());
        }
        OrderSource source = Objects.requireNonNull(request.source(), "source");
        List<OrderItem> items = priceLines(request, station, catalog);
        String trackingCode = codes.get();
        String externalRef = request.externalRef();
        if (externalRef == null || externalRef.isEmpty()) {
            if (source != OrderSource.IN_HOUSE) {
                throw new IllegalArgumentException("an external reference is required for " + source);
            }
            externalRef = "IH-" + codes.get().substring(0, GENERATED_REF_LENGTH);
        }
        return switch (source) {
            case SWIGGY -> new SwiggyOrder(externalRef, trackingCode, station.getId(), station.getCode(),
                    request.customerName(), request.deliveryDistanceKm(), request.paymentMode(), items, now);
            case ZOMATO -> new ZomatoOrder(externalRef, trackingCode, station.getId(), station.getCode(),
                    request.customerName(), request.deliveryDistanceKm(), request.paymentMode(), items, now);
            case IN_HOUSE -> new InHouseOrder(externalRef, trackingCode, station.getId(), station.getCode(),
                    request.customerName(), request.deliveryDistanceKm(), request.paymentMode(), items, now);
        };
    }

    /** Rebuilds an order read from storage or from a snapshot, with its id, status and history. */
    public static Order rehydrate(Long id, OrderSource source, String externalRef, String trackingCode, long stationId,
                                  String brandCode, OrderStatus status, String customerName,
                                  BigDecimal deliveryDistanceKm, PaymentMode paymentMode, List<OrderItem> items,
                                  List<StatusHistoryEntry> history, Instant createdAt) {
        Objects.requireNonNull(source, "source");
        return switch (source) {
            case SWIGGY -> new SwiggyOrder(id, externalRef, trackingCode, stationId, brandCode, status, customerName,
                    deliveryDistanceKm, paymentMode, items, history, createdAt);
            case ZOMATO -> new ZomatoOrder(id, externalRef, trackingCode, stationId, brandCode, status, customerName,
                    deliveryDistanceKm, paymentMode, items, history, createdAt);
            case IN_HOUSE -> new InHouseOrder(id, externalRef, trackingCode, stationId, brandCode, status,
                    customerName, deliveryDistanceKm, paymentMode, items, history, createdAt);
        };
    }

    private static List<OrderItem> priceLines(OrderRequest request, Station station, MenuCatalog catalog) {
        if (request.items() == null) {
            throw new IllegalArgumentException("items are required");
        }
        List<OrderItem> lines = new ArrayList<>(request.items().size());
        for (OrderItemRequest line : request.items()) {
            Objects.requireNonNull(line, "item line");
            MenuItem item = catalog.item(line.itemId())
                    .orElseThrow(() -> new IllegalArgumentException("unknown item " + line.itemId()));
            if (item.getStationId() != station.getId() || !item.isActive()) {
                throw new IllegalArgumentException("item " + item.getId() + " is not orderable at " + station.getCode());
            }
            lines.add(new OrderItem(item.getId(), item.getName(), item.getPrice(), line.quantity()));
        }
        return lines;
    }

    private static String randomCode() {
        StringBuilder code = new StringBuilder(TRACKING_CODE_LENGTH);
        for (int i = 0; i < TRACKING_CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
