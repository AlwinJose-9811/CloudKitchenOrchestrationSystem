package com.cloudkitchen.dao;

import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.dto.ReportRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Persistence of orders (SDD 5.2). Every state change is a conditional {@code UPDATE ... WHERE status = ?}; lease
 * methods read the clock themselves and accept no timestamp (SDD 7.11.15). Implementations never commit or close the
 * connection they are given.
 */
public interface OrderDao {

    long insert(Connection c, Order order) throws SQLException;

    void insertItems(Connection c, long orderId, List<OrderItem> items) throws SQLException;

    /** The order with its items. */
    Optional<Order> findById(Connection c, long id) throws SQLException;

    Optional<Long> findIdBySourceAndRef(Connection c, OrderSource source, String externalRef) throws SQLException;

    Optional<Order> findByTrackingCode(Connection c, String code) throws SQLException;

    // ---- recovery, sweeper, reconciliation ----

    /** Non-terminal orders older than {@code orphanAfter}, excluding pending compensation and unexpired leases. */
    List<Order> findStaleNonTerminal(Connection c, Duration orphanAfter, int limit) throws SQLException;

    /** Non-terminal orders to re-enqueue after a restart; excludes {@code compensation_state = 'PENDING'}. */
    List<Order> findNonTerminalForProcessing(Connection c) throws SQLException;

    List<Order> findNonTerminal(Connection c) throws SQLException;

    List<Order> findReconcilable(Connection c, long stationId, Duration dispatchWindow, int limit) throws SQLException;

    // ---- durable compensation ----

    List<Order> findDuePendingCompensations(Connection c, int limit) throws SQLException;

    List<Order> findAllPendingCompensations(Connection c) throws SQLException;

    /** TXC1: records that compensation is owed. */
    boolean markCompensationPending(Connection c, long orderId, String reason, Instant nextAt) throws SQLException;

    /** TXC3: schedules the next attempt after a failed one. */
    void recordCompensationFailure(Connection c, long orderId, Instant nextAt) throws SQLException;

    // ---- leases (owner and clock handled inside the implementation) ----

    /** Takes a free lease, or renews one held by the same owner, and returns the new epoch. */
    OptionalInt acquireLease(Connection c, long orderId, String owner, Duration ttl) throws SQLException;

    boolean renewLease(Connection c, long orderId, int epoch, String owner, Duration ttl) throws SQLException;

    boolean releaseLease(Connection c, long orderId, int epoch, String owner) throws SQLException;

    /** Ends every lease of one owner; returns the affected order ids. */
    List<Long> expireLeasesOf(Connection c, String owner) throws SQLException;

    /** Startup only: no lease can be valid in a fresh process. */
    int clearAllLeases(Connection c) throws SQLException;

    // ---- transitions ----

    /** Owner-fenced transition (epoch, owner and a valid lease are part of the {@code WHERE}); {@code true} if one row changed. */
    boolean transition(Connection c, long orderId, OrderStatus from, OrderStatus to, FencingToken token, Duration ttl)
            throws SQLException;

    /** Plain conditional transition ({@code WHERE status = from}); {@code true} if one row changed. */
    boolean transition(Connection c, long orderId, OrderStatus from, OrderStatus to, Instant now) throws SQLException;

    /** {@code WHERE stock_deducted = 0}: marks the stock as taken exactly once. */
    boolean markStockDeducted(Connection c, long orderId) throws SQLException;

    /** Flips {@code stock_deducted} from 1 to 0; only the caller that gets {@code true} may restore the stock. */
    boolean claimStockRestore(Connection c, long orderId) throws SQLException;

    // ---- queries ----

    Map<OrderStatus, Long> countByStatus(Connection c) throws SQLException;

    List<OrderSummary> findSummaries(Connection c, OrderFilter filter, int limit, int offset) throws SQLException;

    long count(Connection c, OrderFilter filter) throws SQLException;

    List<ReportRow> findForReport(Connection c, Instant fromUtc, Instant toUtc) throws SQLException;

    /** Inserts an order read from a verified snapshot that is missing from the database. */
    long insertRecovered(Connection c, Order order) throws SQLException;
}
