package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.db.LeaseStampContext;
import com.cloudkitchen.db.LeaseStampHook;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderFactory;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.PaymentResult;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.dto.ReportRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * JDBC access to {@code orders} and {@code order_items}. Every state change is a conditional {@code UPDATE}; the
 * return value says whether this caller changed the row (FR-LIF-03). Lease statements read the clock themselves, as
 * the last action before they execute, and accept no timestamp (SDD 7.11.15, S-1/S-2).
 */
public final class JdbcOrderDao implements OrderDao {

    private static final String TERMINAL = "'DELIVERED','CANCELLED','FAILED'";
    private static final String NON_TERMINAL = "'PLACED','VALIDATED','ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY'";
    private static final String NOT_PENDING = "(o.compensation_state IS NULL OR o.compensation_state <> 'PENDING')";

    private static final String SELECT_ORDER = "SELECT o.id, o.tracking_code, o.source, o.external_ref, o.station_id,"
            + " s.code AS brand_code, o.status, o.customer_name, o.delivery_distance_km, o.payment_mode, o.created_at"
            + " FROM orders o JOIN stations s ON s.id = o.station_id";

    private final Clock clock;
    private final Duration leaseMargin;
    private final LeaseStampHook hook;

    public JdbcOrderDao(Clock clock, Duration leaseMargin, LeaseStampHook hook) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseMargin = Objects.requireNonNull(leaseMargin, "leaseMargin");
        this.hook = Objects.requireNonNull(hook, "hook");
    }

    public JdbcOrderDao(Clock clock, Duration leaseMargin) {
        this(clock, leaseMargin, LeaseStampHook.NO_OP);
    }

    // ---- insert and read ----

    @Override
    public long insert(Connection c, Order order) throws SQLException {
        return insertRow(c, order, order.getStatus());
    }

    @Override
    public long insertRecovered(Connection c, Order order) throws SQLException {
        return insertRow(c, order, order.getStatus());
    }

    private long insertRow(Connection c, Order order, OrderStatus status) throws SQLException {
        PaymentResult payment = order.processPayment();
        Instant now = clock.instant();
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO orders (tracking_code, source, external_ref, station_id, status, customer_name, delivery_distance_km,"
                        + " payment_mode, payment_status, total_amount, platform_fee, stock_deducted, estimated_delivery_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, order.getTrackingCode());
            p.setString(2, order.getSource().name());
            p.setString(3, order.getExternalRef());
            p.setLong(4, order.getStationId());
            p.setString(5, status.name());
            p.setString(6, order.getCustomerName());
            p.setBigDecimal(7, order.getDeliveryDistanceKm());
            p.setString(8, order.getPaymentMode().name());
            p.setString(9, payment.status().name());
            p.setBigDecimal(10, order.getSubtotal());
            p.setBigDecimal(11, payment.platformFee());
            Sql.setTime(p, 12, order.getEstimatedDeliveryAt());
            Sql.setTime(p, 13, order.getCreatedAt());
            Sql.setTime(p, 14, now);
            p.executeUpdate();
            return Sql.generatedKey(p);
        }
    }

    @Override
    public void insertItems(Connection c, long orderId, List<OrderItem> items) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO order_items (order_id, item_id, item_name, unit_price, quantity) VALUES (?, ?, ?, ?, ?)")) {
            for (OrderItem item : items) {
                p.setLong(1, orderId);
                p.setLong(2, item.getItemId());
                p.setString(3, item.getItemName());
                p.setBigDecimal(4, item.getUnitPrice());
                p.setInt(5, item.getQuantity());
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    @Override
    public Optional<Order> findById(Connection c, long id) throws SQLException {
        List<Order> found = query(c, SELECT_ORDER + " WHERE o.id = ?", p -> p.setLong(1, id), true);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<Long> findIdBySourceAndRef(Connection c, OrderSource source, String externalRef) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT id FROM orders WHERE source = ? AND external_ref = ?")) {
            p.setString(1, source.name());
            p.setString(2, externalRef);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Order> findByTrackingCode(Connection c, String code) throws SQLException {
        List<Order> found = query(c, SELECT_ORDER + " WHERE o.tracking_code = ?", p -> p.setString(1, code), true);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<OrderState> findState(Connection c, long orderId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT status, compensation_state, compensation_reason, compensation_attempts, stock_deducted, claim_epoch, claimed_by"
                        + " FROM orders WHERE id = ?")) {
            p.setLong(1, orderId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next()
                        ? Optional.of(new OrderState(OrderStatus.valueOf(rs.getString("status")), rs.getString("compensation_state"),
                                rs.getString("compensation_reason"), rs.getInt("compensation_attempts"),
                                rs.getBoolean("stock_deducted"), rs.getInt("claim_epoch"), rs.getString("claimed_by")))
                        : Optional.empty();
            }
        }
    }

    @Override
    public List<OrderItem> findItems(Connection c, long orderId) throws SQLException {
        return loadItems(c, List.of(orderId)).getOrDefault(orderId, List.of());
    }

    // ---- recovery, sweeper, reconciliation ----

    @Override
    public List<Order> findStaleNonTerminal(Connection c, Duration orphanAfter, int limit) throws SQLException {
        Instant now = LeaseStampContext.freshNow(clock, hook);
        return query(c, SELECT_ORDER + " WHERE o.status IN (" + NON_TERMINAL + ") AND o.updated_at < ? AND " + NOT_PENDING
                + " AND (o.lease_expires_at IS NULL OR o.lease_expires_at <= ?) ORDER BY o.updated_at, o.id LIMIT ?", p -> {
                    Sql.setTime(p, 1, now.minus(orphanAfter));
                    Sql.setTime(p, 2, now);
                    p.setInt(3, limit);
                }, false);
    }

    @Override
    public boolean isStaleNonTerminal(Connection c, long orderId, Duration orphanAfter) throws SQLException {
        Instant now = LeaseStampContext.freshNow(clock, hook);
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM orders o WHERE o.id = ? AND o.status IN (" + NON_TERMINAL
                + ") AND o.updated_at < ? AND " + NOT_PENDING + " AND (o.lease_expires_at IS NULL OR o.lease_expires_at <= ?)")) {
            p.setLong(1, orderId);
            Sql.setTime(p, 2, now.minus(orphanAfter));
            Sql.setTime(p, 3, now);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public List<Order> findNonTerminalForProcessing(Connection c) throws SQLException {
        return query(c, SELECT_ORDER + " WHERE o.status IN (" + NON_TERMINAL + ") AND " + NOT_PENDING + " ORDER BY o.id", p -> { }, false);
    }

    @Override
    public List<Order> findNonTerminal(Connection c) throws SQLException {
        return query(c, SELECT_ORDER + " WHERE o.status IN (" + NON_TERMINAL + ") ORDER BY o.id", p -> { }, false);
    }

    @Override
    public List<Order> findReconcilable(Connection c, long stationId, Duration dispatchWindow, int limit) throws SQLException {
        Instant now = LeaseStampContext.freshNow(clock, hook);
        return query(c, SELECT_ORDER + " WHERE o.station_id = ? AND " + NOT_PENDING
                + " AND (o.lease_expires_at IS NULL OR o.lease_expires_at <= ?)"
                + " AND (o.status IN ('ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY') OR (o.status = 'VALIDATED' AND o.updated_at < ?))"
                + " ORDER BY o.id LIMIT ?", p -> {
                    p.setLong(1, stationId);
                    Sql.setTime(p, 2, now);
                    Sql.setTime(p, 3, now.minus(dispatchWindow));
                    p.setInt(4, limit);
                }, false);
    }

    // ---- durable compensation ----

    @Override
    public List<Order> findDuePendingCompensations(Connection c, int limit) throws SQLException {
        Instant now = clock.instant();
        return query(c, SELECT_ORDER + " WHERE o.compensation_state = 'PENDING' AND o.status = 'VALIDATED'"
                + " AND (o.compensation_next_at IS NULL OR o.compensation_next_at <= ?) ORDER BY o.id LIMIT ?", p -> {
                    Sql.setTime(p, 1, now);
                    p.setInt(2, limit);
                }, false);
    }

    @Override
    public List<Order> findAllPendingCompensations(Connection c) throws SQLException {
        return query(c, SELECT_ORDER + " WHERE o.compensation_state = 'PENDING' AND o.status = 'VALIDATED' ORDER BY o.id", p -> { }, false);
    }

    @Override
    public boolean markCompensationPending(Connection c, long orderId, String reason, Instant nextAt) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET compensation_state = 'PENDING', compensation_reason = ?, compensation_attempts = 1,"
                        + " compensation_next_at = ? WHERE id = ? AND status = 'VALIDATED' AND compensation_state IS NULL")) {
            p.setString(1, reason);
            Sql.setTime(p, 2, nextAt);
            p.setLong(3, orderId);
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public void recordCompensationFailure(Connection c, long orderId, Instant nextAt) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET compensation_attempts = compensation_attempts + 1, compensation_next_at = ?"
                        + " WHERE id = ? AND compensation_state = 'PENDING'")) {
            Sql.setTime(p, 1, nextAt);
            p.setLong(2, orderId);
            p.executeUpdate();
        }
    }

    // ---- leases ----

    @Override
    public OptionalInt acquireLease(Connection c, long orderId, String owner, Duration ttl) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET claim_epoch = claim_epoch + 1, claimed_by = ?, lease_expires_at = ?"
                        + " WHERE id = ? AND status NOT IN (" + TERMINAL + ") AND compensation_state IS NULL"
                        + " AND (lease_expires_at IS NULL OR lease_expires_at <= ? OR claimed_by = ?)")) {
            p.setString(1, owner);
            p.setLong(3, orderId);
            p.setString(5, owner);
            Instant now = LeaseStampContext.freshNow(clock, hook); // last action before executing (S-2)
            Sql.setTime(p, 2, now.plus(ttl));
            Sql.setTime(p, 4, now);
            if (p.executeUpdate() != 1) {
                return OptionalInt.empty();
            }
        }
        try (PreparedStatement p = c.prepareStatement("SELECT claim_epoch FROM orders WHERE id = ?")) {
            p.setLong(1, orderId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            }
        }
    }

    @Override
    public boolean renewLease(Connection c, long orderId, int epoch, String owner, Duration ttl) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET lease_expires_at = ? WHERE id = ? AND claim_epoch = ? AND claimed_by = ?"
                        + " AND lease_expires_at > ? AND status NOT IN (" + TERMINAL + ")")) {
            p.setLong(2, orderId);
            p.setInt(3, epoch);
            p.setString(4, owner);
            LeaseStampContext.Stamp stamp = LeaseStampContext.stamp(clock, leaseMargin, hook);
            Sql.setTime(p, 1, stamp.now().plus(ttl));
            Sql.setTime(p, 5, stamp.usableAfter());
            boolean renewed = p.executeUpdate() == 1;
            LeaseStampContext.afterExecute(clock, hook);
            return renewed;
        }
    }

    @Override
    public boolean releaseLease(Connection c, long orderId, int epoch, String owner) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET lease_expires_at = NULL, claimed_by = NULL WHERE id = ? AND claim_epoch = ? AND claimed_by = ?")) {
            p.setLong(1, orderId);
            p.setInt(2, epoch);
            p.setString(3, owner);
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public List<Long> expireLeasesOf(Connection c, String owner) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT id FROM orders WHERE claimed_by = ? AND status NOT IN (" + TERMINAL + ") ORDER BY id FOR UPDATE")) {
            p.setString(1, owner);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET lease_expires_at = NULL, claimed_by = NULL WHERE claimed_by = ? AND status NOT IN (" + TERMINAL + ")")) {
            p.setString(1, owner);
            p.executeUpdate();
        }
        return ids;
    }

    @Override
    public int clearAllLeases(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET lease_expires_at = NULL, claimed_by = NULL WHERE lease_expires_at IS NOT NULL")) {
            return p.executeUpdate();
        }
    }

    // ---- transitions ----

    @Override
    public boolean transition(Connection c, long orderId, OrderStatus from, OrderStatus to, FencingToken token, Duration ttl)
            throws SQLException {
        String sql = to.isTerminal()
                ? "UPDATE orders SET status = ?, updated_at = ?, lease_expires_at = NULL, claimed_by = NULL"
                        + " WHERE id = ? AND status = ? AND claim_epoch = ? AND claimed_by = ? AND lease_expires_at > ?"
                : to == OrderStatus.ROUTED
                        ? "UPDATE orders SET status = ?, updated_at = ?, lease_expires_at = ?"
                                + " WHERE id = ? AND status = ? AND claim_epoch = ? AND claimed_by = ? AND lease_expires_at > ?"
                                + " AND compensation_state IS NULL"
                        : "UPDATE orders SET status = ?, updated_at = ?, lease_expires_at = ?"
                                + " WHERE id = ? AND status = ? AND claim_epoch = ? AND claimed_by = ? AND lease_expires_at > ?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, to.name());
            int next = 3;
            if (to.isTerminal()) {
                p.setLong(next++, token.orderId());
            } else {
                next++; // lease_expires_at is bound from the stamp below
                p.setLong(next++, token.orderId());
            }
            p.setString(next++, from.name());
            p.setInt(next++, token.epoch());
            p.setString(next++, token.owner());
            int usableIndex = next;
            LeaseStampContext.Stamp stamp = LeaseStampContext.stamp(clock, leaseMargin, hook);
            Sql.setTime(p, 2, stamp.now());
            if (!to.isTerminal()) {
                Sql.setTime(p, 3, stamp.now().plus(ttl));
            }
            Sql.setTime(p, usableIndex, stamp.usableAfter());
            boolean changed = p.executeUpdate() == 1;
            LeaseStampContext.afterExecute(clock, hook);
            return changed;
        }
    }

    @Override
    public boolean transition(Connection c, long orderId, OrderStatus from, OrderStatus to, Instant now) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE orders SET status = ?, updated_at = ?");
        if (to == OrderStatus.FAILED || to == OrderStatus.CANCELLED) {
            sql.append(", compensation_state = CASE WHEN compensation_state = 'PENDING' THEN 'RESOLVED' ELSE compensation_state END");
        }
        if (to.isTerminal()) {
            sql.append(", lease_expires_at = NULL, claimed_by = NULL");
        }
        sql.append(" WHERE id = ? AND status = ?");
        if (to == OrderStatus.ROUTED) {
            sql.append(" AND compensation_state IS NULL");
        }
        try (PreparedStatement p = c.prepareStatement(sql.toString())) {
            p.setString(1, to.name());
            Sql.setTime(p, 2, now);
            p.setLong(3, orderId);
            p.setString(4, from.name());
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public boolean markStockDeducted(Connection c, long orderId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE orders SET stock_deducted = 1 WHERE id = ? AND stock_deducted = 0")) {
            p.setLong(1, orderId);
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public boolean claimStockRestore(Connection c, long orderId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE orders SET stock_deducted = 0 WHERE id = ? AND stock_deducted = 1")) {
            p.setLong(1, orderId);
            return p.executeUpdate() == 1;
        }
    }

    // ---- queries ----

    @Override
    public Map<OrderStatus, Long> countByStatus(Connection c) throws SQLException {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            counts.put(status, 0L);
        }
        try (PreparedStatement p = c.prepareStatement("SELECT status, COUNT(*) FROM orders GROUP BY status");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                counts.put(OrderStatus.valueOf(rs.getString(1)), rs.getLong(2));
            }
        }
        return counts;
    }

    @Override
    public List<OrderSummary> findSummaries(Connection c, OrderFilter filter, int limit, int offset) throws SQLException {
        List<Object> values = new ArrayList<>();
        String sql = filteredSql("SELECT o.id, o.source, s.code AS brand_code, o.external_ref, o.status, o.total_amount, o.created_at"
                + " FROM orders o JOIN stations s ON s.id = o.station_id", filter, " ORDER BY o.id DESC LIMIT ? OFFSET ?", values);
        try (PreparedStatement p = c.prepareStatement(sql)) {
            int next = bind(p, values);
            p.setInt(next++, limit);
            p.setInt(next, offset);
            try (ResultSet rs = p.executeQuery()) {
                List<OrderSummary> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new OrderSummary(rs.getLong("id"), OrderSource.valueOf(rs.getString("source")), rs.getString("brand_code"),
                            rs.getString("external_ref"), OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("total_amount"),
                            Sql.getTime(rs, "created_at")));
                }
                return rows;
            }
        }
    }

    @Override
    public long count(Connection c, OrderFilter filter) throws SQLException {
        List<Object> values = new ArrayList<>();
        String sql = filteredSql("SELECT COUNT(*) FROM orders o", filter, "", values);
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, values);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Assembles {@code head + WHERE ... + tail}. The only text appended here is fixed literals and {@code ?}
     * placeholders; every filter value goes into {@code values} and is bound by the caller (NFR-SEC-02).
     */
    private static String filteredSql(String head, OrderFilter filter, String tail, List<Object> values) {
        StringBuilder sql = new StringBuilder(head);
        boolean first = true;
        if (filter.status() != null) {
            sql.append(first ? " WHERE " : " AND ").append("o.status = ?");
            values.add(filter.status().name());
            first = false;
        }
        if (filter.source() != null) {
            sql.append(first ? " WHERE " : " AND ").append("o.source = ?");
            values.add(filter.source().name());
            first = false;
        }
        if (filter.externalRefContains() != null) {
            sql.append(first ? " WHERE " : " AND ").append("o.external_ref LIKE ? ESCAPE '!'");
            values.add("%" + filter.externalRefContains().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            first = false;
        }
        if (filter.fromUtc() != null) {
            sql.append(first ? " WHERE " : " AND ").append("o.created_at >= ?");
            values.add(filter.fromUtc());
            first = false;
        }
        if (filter.toUtc() != null) {
            sql.append(first ? " WHERE " : " AND ").append("o.created_at < ?");
            values.add(filter.toUtc());
        }
        return sql.append(tail).toString();
    }

    private static int bind(PreparedStatement p, List<Object> values) throws SQLException {
        int index = 1;
        for (Object value : values) {
            if (value instanceof Instant instant) {
                Sql.setTime(p, index++, instant);
            } else {
                p.setString(index++, (String) value);
            }
        }
        return index;
    }

    @Override
    public List<ReportRow> findForReport(Connection c, Instant fromUtc, Instant toUtc) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT o.id, o.created_at, o.source, s.code AS brand_code, o.external_ref, o.status, o.total_amount, o.platform_fee,"
                        + " o.payment_mode, o.payment_status, (SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i WHERE i.order_id = o.id) AS item_count"
                        + " FROM orders o JOIN stations s ON s.id = o.station_id WHERE o.created_at >= ? AND o.created_at < ? ORDER BY o.id")) {
            Sql.setTime(p, 1, fromUtc);
            Sql.setTime(p, 2, toUtc);
            try (ResultSet rs = p.executeQuery()) {
                List<ReportRow> rows = new ArrayList<>();
                while (rs.next()) {
                    BigDecimal total = rs.getBigDecimal("total_amount");
                    rows.add(new ReportRow(rs.getLong("id"), Sql.getTime(rs, "created_at"), OrderSource.valueOf(rs.getString("source")),
                            rs.getString("brand_code"), rs.getString("external_ref"), OrderStatus.valueOf(rs.getString("status")),
                            rs.getInt("item_count"), total, rs.getBigDecimal("platform_fee"), total,
                            PaymentMode.valueOf(rs.getString("payment_mode")), PaymentStatus.valueOf(rs.getString("payment_status"))));
                }
                return rows;
            }
        }
    }

    // ---- reconstruction ----

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement p) throws SQLException;
    }

    private record Row(long id, String trackingCode, OrderSource source, String externalRef, long stationId, String brandCode,
                       OrderStatus status, String customerName, BigDecimal distance, PaymentMode paymentMode, Instant createdAt) {
    }

    private List<Order> query(Connection c, String sql, Binder binder, boolean withHistory) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            binder.bind(p);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("id"), rs.getString("tracking_code"), OrderSource.valueOf(rs.getString("source")),
                            rs.getString("external_ref"), rs.getLong("station_id"), rs.getString("brand_code"),
                            OrderStatus.valueOf(rs.getString("status")), rs.getString("customer_name"),
                            rs.getBigDecimal("delivery_distance_km"), PaymentMode.valueOf(rs.getString("payment_mode")),
                            Sql.getTime(rs, "created_at")));
                }
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Row::id).toList();
        Map<Long, List<OrderItem>> items = loadItems(c, ids);
        List<Order> orders = new ArrayList<>(rows.size());
        for (Row row : rows) {
            List<StatusHistoryEntry> history = withHistory ? loadHistory(c, row.id()) : List.of();
            orders.add(OrderFactory.rehydrate(row.id(), row.source(), row.externalRef(), row.trackingCode(), row.stationId(),
                    row.brandCode(), row.status(), row.customerName(), row.distance(), row.paymentMode(),
                    items.getOrDefault(row.id(), List.of()), history, row.createdAt()));
        }
        return orders;
    }

    private Map<Long, List<OrderItem>> loadItems(Connection c, List<Long> orderIds) throws SQLException {
        Map<Long, List<OrderItem>> byOrder = new LinkedHashMap<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT order_id, item_id, item_name, unit_price, quantity FROM order_items WHERE order_id IN ("
                        + Sql.placeholders(orderIds.size()) + ") ORDER BY order_id, item_id")) {
            for (int i = 0; i < orderIds.size(); i++) {
                p.setLong(i + 1, orderIds.get(i));
            }
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    byOrder.computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>()).add(new OrderItem(rs.getLong("item_id"),
                            rs.getString("item_name"), rs.getBigDecimal("unit_price"), rs.getInt("quantity")));
                }
            }
        }
        return byOrder;
    }

    private static List<StatusHistoryEntry> loadHistory(Connection c, long orderId) throws SQLException {
        return new JdbcStatusHistoryDao().findByOrderId(c, orderId);
    }
}
