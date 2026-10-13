package com.cloudkitchen.domain;

import com.cloudkitchen.domain.contract.Payable;
import com.cloudkitchen.domain.contract.Trackable;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.util.MoneyUtil;
import com.cloudkitchen.util.TimeUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An order as read or just created. It is a consistent snapshot: every field is validated once, in the constructor,
 * and nothing can be changed afterwards. There is intentionally no status setter: a status change is a database
 * transition (a conditional update, SDD 6.4), and the next read gives a new {@code Order}. Which transitions are legal
 * is decided only by {@link OrderStatus}.
 *
 * <p>The subclasses differ in how the delivery estimate and the payment are computed (SDD 4.4). Only
 * {@link OrderFactory} can construct them.
 */
public abstract class Order implements Trackable, Payable {

    public static final int MAX_ITEMS = 20;
    public static final int MAX_CUSTOMER_NAME_LENGTH = 100;
    /** The database column is {@code DECIMAL(5,2)}. */
    public static final BigDecimal MAX_DISTANCE_KM = new BigDecimal("999.99");

    private static final Pattern EXTERNAL_REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern TRACKING_CODE = Pattern.compile("[A-Z0-9]{12}");
    private static final Duration BASE_PREPARATION = Duration.ofMinutes(15);

    private final Long id;
    private final OrderSource source;
    private final String externalRef;
    private final String trackingCode;
    private final long stationId;
    private final String brandCode;
    private final OrderStatus status;
    private final String customerName;
    private final BigDecimal deliveryDistanceKm;
    private final PaymentMode paymentMode;
    private final List<OrderItem> items;
    private final List<StatusHistoryEntry> history;
    private final Instant createdAt;

    /** Full constructor: an order read back from storage, with its id, status and history. */
    protected Order(Long id, OrderSource source, String externalRef, String trackingCode, long stationId,
                    String brandCode, OrderStatus status, String customerName, BigDecimal deliveryDistanceKm,
                    PaymentMode paymentMode, List<OrderItem> items, List<StatusHistoryEntry> history,
                    Instant createdAt) {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        this.id = id;
        this.source = Objects.requireNonNull(source, "source");
        if (externalRef == null || !EXTERNAL_REF.matcher(externalRef).matches()) {
            throw new IllegalArgumentException("externalRef must match " + EXTERNAL_REF.pattern());
        }
        this.externalRef = externalRef;
        if (trackingCode == null || !TRACKING_CODE.matcher(trackingCode).matches()) {
            throw new IllegalArgumentException("trackingCode must match " + TRACKING_CODE.pattern());
        }
        this.trackingCode = trackingCode;
        if (stationId <= 0) {
            throw new IllegalArgumentException("stationId must be positive");
        }
        this.stationId = stationId;
        if (brandCode == null || brandCode.isBlank()) {
            throw new IllegalArgumentException("brandCode must not be blank");
        }
        this.brandCode = brandCode;
        this.status = Objects.requireNonNull(status, "status");
        if (customerName == null || customerName.isBlank() || customerName.length() > MAX_CUSTOMER_NAME_LENGTH) {
            throw new IllegalArgumentException("customerName must be 1-" + MAX_CUSTOMER_NAME_LENGTH + " characters");
        }
        this.customerName = customerName;
        this.deliveryDistanceKm = requireDistance(deliveryDistanceKm);
        Objects.requireNonNull(paymentMode, "paymentMode");
        if (!paymentMode.allowedFor(source)) {
            throw new IllegalArgumentException(paymentMode + " is not allowed for " + source);
        }
        this.paymentMode = paymentMode;
        this.items = copyItems(items);
        this.createdAt = TimeUtil.truncateToMillis(Objects.requireNonNull(createdAt, "createdAt"));
        this.history = copyHistory(history, id, status);
    }

    /** Minimal constructor: a new order that has no id and no history yet and starts in {@code PLACED}. */
    protected Order(OrderSource source, String externalRef, String trackingCode, long stationId, String brandCode,
                    String customerName, BigDecimal deliveryDistanceKm, PaymentMode paymentMode,
                    List<OrderItem> items, Instant createdAt) {
        this(null, source, externalRef, trackingCode, stationId, brandCode, OrderStatus.PLACED, customerName,
                deliveryDistanceKm, paymentMode, items, List.of(), createdAt);
    }

    private static BigDecimal requireDistance(BigDecimal distance) {
        Objects.requireNonNull(distance, "deliveryDistanceKm");
        if (!MoneyUtil.fitsTwoDecimals(distance) || distance.signum() < 0 || distance.compareTo(MAX_DISTANCE_KM) > 0) {
            throw new IllegalArgumentException("deliveryDistanceKm must be 0.00-" + MAX_DISTANCE_KM + " with at most two decimals");
        }
        return distance.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static List<OrderItem> copyItems(List<OrderItem> source) {
        Objects.requireNonNull(source, "items");
        if (source.isEmpty() || source.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("an order has 1-" + MAX_ITEMS + " lines");
        }
        Set<Long> seen = new HashSet<>();
        List<OrderItem> copy = new ArrayList<>(source.size());
        for (OrderItem item : source) {
            Objects.requireNonNull(item, "item");
            if (!seen.add(item.getItemId())) {
                throw new IllegalArgumentException("item " + item.getItemId() + " appears on two lines");
            }
            copy.add(item);
        }
        return Collections.unmodifiableList(copy);
    }

    /** The history must belong to this order and form an unbroken chain ending in the current status. */
    private static List<StatusHistoryEntry> copyHistory(List<StatusHistoryEntry> source, Long orderId,
                                                        OrderStatus current) {
        Objects.requireNonNull(source, "history");
        if (source.isEmpty()) {
            return List.of();
        }
        if (orderId == null) {
            throw new IllegalArgumentException("an order without an id cannot have history");
        }
        List<StatusHistoryEntry> copy = new ArrayList<>(source.size());
        OrderStatus expectedFrom = null;
        for (StatusHistoryEntry entry : source) {
            Objects.requireNonNull(entry, "history entry");
            if (entry.orderId() != orderId) {
                throw new IllegalArgumentException("history entry belongs to order " + entry.orderId());
            }
            if (entry.from() != expectedFrom) {
                throw new IllegalArgumentException("history is not a chain: expected from " + expectedFrom
                        + " but was " + entry.from());
            }
            expectedFrom = entry.to();
            copy.add(entry);
        }
        if (expectedFrom != current) {
            throw new IllegalArgumentException("history ends in " + expectedFrom + " but the status is " + current);
        }
        return Collections.unmodifiableList(copy);
    }

    // ---- Trackable ----

    @Override
    public OrderStatus getStatus() {
        return status;
    }

    @Override
    public List<StatusHistoryEntry> getHistory() {
        return history;
    }

    @Override
    public String getTrackingCode() {
        return trackingCode;
    }

    // ---- Payable ----

    /** The customer owes the sum of the lines; the design has no tax, delivery fee or discount. */
    @Override
    public BigDecimal getPayableAmount() {
        return getSubtotal();
    }

    @Override
    public abstract PaymentResult processPayment();

    // ---- source-specific behaviour ----

    /** How long until the order reaches the customer, counted from the time it was placed. */
    public abstract Duration calculateDeliveryEstimate();

    /** When the order is expected to arrive: {@code createdAt + calculateDeliveryEstimate()}. */
    public Instant getEstimatedDeliveryAt() {
        return createdAt.plus(calculateDeliveryEstimate());
    }

    /** Shared by all sources: 15 minutes plus one minute for every two items ordered. */
    protected Duration basePreparation() {
        return BASE_PREPARATION.plusMinutes(totalQuantity() / 2);
    }

    /** Minutes needed for the distance at {@code minutesPerKm}, rounded up to a whole minute. */
    protected Duration travelTime(BigDecimal minutesPerKm) {
        BigDecimal minutes = deliveryDistanceKm.multiply(minutesPerKm).setScale(0, RoundingMode.CEILING);
        return Duration.ofMinutes(minutes.longValueExact());
    }

    // ---- common state ----

    /** Sum of the line totals. */
    public BigDecimal getSubtotal() {
        List<BigDecimal> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            lines.add(item.lineTotal());
        }
        return MoneyUtil.sum(lines);
    }

    public int totalQuantity() {
        int total = 0;
        for (OrderItem item : items) {
            total += item.getQuantity();
        }
        return total;
    }

    /** {@code null} until the order has been stored. */
    public Long getId() {
        return id;
    }

    public OrderSource getSource() {
        return source;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public long getStationId() {
        return stationId;
    }

    public String getBrandCode() {
        return brandCode;
    }

    /** Personal data: never log it. */
    public String getCustomerName() {
        return customerName;
    }

    public BigDecimal getDeliveryDistanceKm() {
        return deliveryDistanceKm;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Two orders are the same order when they come from the same source with the same external reference. */
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Order that && source == that.source && externalRef.equals(that.externalRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, externalRef);
    }

    /** Never includes the customer name (personal data). Subclasses add their source label. */
    protected String describe(String label) {
        return label + "[id=" + id + ", ref=" + externalRef + ", brand=" + brandCode + ", status=" + status + "]";
    }
}
