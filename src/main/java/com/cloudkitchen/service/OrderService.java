package com.cloudkitchen.service;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.dao.OrderDao.OrderState;
import com.cloudkitchen.dao.MenuItemDao;
import com.cloudkitchen.dao.StatusHistoryDao;
import com.cloudkitchen.dao.StockDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.db.TxWork;
import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderFactory;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.dto.ValidationResult;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.InsufficientStockException;
import com.cloudkitchen.exception.LeaseStampStaleException;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.pipeline.OrderDispatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use cases of the order lifecycle. Every workflow that changes more than one row is exactly one database
 * transaction (SDD 13.1, TXR-1): the conditional status update comes first and everything after it runs only if it
 * changed one row (TXR-3). Metrics, log events and the queue are touched only after the transaction committed and
 * only by the caller that won the update (NFR-REL-05).
 *
 * <p>This class never imports JDBC types. Work is passed to {@link Database#inTransaction} as {@link TxWork}; the
 * unit of a status change is exposed as {@link #transitionWork}, which a caller can run inside a transaction of its
 * own (this replaces the SDD 5.3 overload taking a {@code Connection}, decision recorded for Phase 3).
 *
 * <p>Two API groups exist and must not be mixed (SDD 7.11.12, F-6): the <em>owner-fenced</em> methods used only by
 * a {@code StationWorker}, and the unfenced <em>non-owner</em> methods for staff, the submitter, the sweeper,
 * the operator and recovery.
 */
public final class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
    private static final String SYSTEM = "SYSTEM";

    /** How a queue-full compensation ended (SDD 7.10). */
    public enum CompensationOutcome { COMPENSATED, PENDING, ALREADY_HANDLED, INDETERMINATE }

    /** Why a cancel did or did not happen (RR-7). */
    public enum CancelOutcome { CANCELLED, AWAITING_COMPENSATION, NOT_CANCELLABLE, NOT_FOUND, CONTENDED }

    /** Tunables taken from the configuration. */
    public record Settings(Duration leaseTtl, int compensationRetryMax, Duration compensationBackoff,
                           Duration compensationBackoffMax, int compensationAlertAttempts, int leaseStampRetry) {

        public static Settings from(AppConfig config) {
            return new Settings(Duration.ofSeconds(config.workerLeaseTtlSeconds()), config.compensationRetryMax(),
                    Duration.ofSeconds(config.compensationBackoffSeconds()), Duration.ofSeconds(config.compensationBackoffMaxSeconds()),
                    config.compensationAlertAttempts(), config.workerLeaseStampRetry());
        }
    }

    /** What the single transaction of a status change did. */
    record TransitionResult(boolean applied, boolean stockRestored) {
        static final TransitionResult NOT_APPLIED = new TransitionResult(false, false);
    }

    private final Database database;
    private final OrderDao orders;
    private final StockDao stock;
    private final StatusHistoryDao history;
    private final MenuItemDao menu;
    private final OrderValidator validator;
    private final RoutingService routing;
    private final OrderDispatcher dispatcher;
    private final InFlightRegistry inFlight;
    private final MetricsRegistry metrics;
    private final OrderEventLog events;
    private final Clock clock;
    private final SubmissionHook hook;
    private final Settings settings;

    private volatile boolean accepting;
    private volatile boolean everAccepted;

    public OrderService(Database database, OrderDao orders, StockDao stock, StatusHistoryDao history, MenuItemDao menu,
                        OrderValidator validator, RoutingService routing, OrderDispatcher dispatcher, InFlightRegistry inFlight,
                        MetricsRegistry metrics, OrderEventLog events, Clock clock, SubmissionHook hook, Settings settings) {
        this.database = Objects.requireNonNull(database, "database");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.history = Objects.requireNonNull(history, "history");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hook = Objects.requireNonNull(hook, "hook");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    // ------------------------------------------------------------------------------------------------------
    // Submission (FR-ORD-01, TX1)
    // ------------------------------------------------------------------------------------------------------

    public void setAccepting(boolean accepting) {
        if (accepting) {
            everAccepted = true;
        }
        this.accepting = accepting;
    }

    public boolean isAccepting() {
        return accepting;
    }

    /**
     * The single entry point for new orders (all sources). Never throws for a bad request or a database outage: the
     * result says what happened. The order is validated, stored with its stock deduction in one transaction (TX1),
     * handed to the station queue and moved to {@code ROUTED}.
     */
    public SubmissionResult submitOrder(OrderRequest request) {
        metrics.incrementSubmitted();
        if (!accepting) {
            return rejectedBeforeWork(everAccepted ? SubmissionResult.SHUTTING_DOWN : SubmissionResult.STARTING_UP);
        }
        boolean held = false;
        try {
            if (request != null && request.source() != null && request.externalRef() != null && !request.externalRef().isBlank()) {
                if (!inFlight.tryAcquire(request.source(), request.externalRef())) {
                    metrics.incrementRejected();
                    events.log(EventType.ORDER_DUPLICATE);
                    return SubmissionResult.duplicateInFlight();
                }
                held = true;
            }
            return submitHeld(request);
        } finally {
            if (held) {
                inFlight.release(request.source(), request.externalRef());
            }
        }
    }

    private SubmissionResult rejectedBeforeWork(String reason) {
        metrics.incrementRejected();
        events.log(EventType.ORDER_REJECTED, 0L, reason);
        return SubmissionResult.rejected(reason);
    }

    private SubmissionResult submitHeld(OrderRequest request) {
        MenuCatalog catalog;
        try {
            catalog = loadCatalog(request);
        } catch (CloudKitchenException e) {
            return temporaryFailure(e);
        }
        ValidationResult validation = validator.validate(request, catalog);
        if (!validation.isValid()) {
            metrics.incrementRejected();
            events.log(EventType.ORDER_REJECTED, 0L, SubmissionResult.VALIDATION);
            return SubmissionResult.rejected(SubmissionResult.VALIDATION, validation.errors());
        }
        Station station = routing.stationFor(request.brandCode()).orElseThrow();
        Order order = OrderFactory.create(request, station, catalog, clock.instant());

        long orderId;
        try {
            orderId = database.inTransaction(createWork(order));
        } catch (InsufficientStockException e) {
            metrics.incrementRejected();
            events.log(EventType.ORDER_REJECTED, 0L, SubmissionResult.INSUFFICIENT_STOCK);
            return SubmissionResult.rejected(SubmissionResult.INSUFFICIENT_STOCK);
        } catch (CloudKitchenException e) {
            if (SqlErrors.isDuplicateKey(e)) {
                return duplicateOf(order);
            }
            return temporaryFailure(e);
        }
        events.log(EventType.ORDER_VALIDATED, orderId);
        hook.at(SubmissionHook.Stage.AFTER_TX1);

        try {
            dispatcher.dispatch(orderId, order.getBrandCode());
        } catch (QueueFullException full) {
            return afterQueueFull(orderId, full.getReason().name());
        }
        hook.at(SubmissionHook.Stage.AFTER_OFFER);
        routeAfterOffer(orderId);
        hook.at(SubmissionHook.Stage.AFTER_ROUTED);
        return SubmissionResult.accepted(orderId);
    }

    private MenuCatalog loadCatalog(OrderRequest request) throws CloudKitchenException {
        Set<Long> ids = new HashSet<>();
        if (request != null && request.items() != null) {
            for (OrderItemRequest line : request.items()) {
                if (line != null) {
                    ids.add(line.itemId());
                }
            }
        }
        List<MenuItem> items = ids.isEmpty() ? List.of() : new ArrayList<>(database.withConnection(c -> menu.findByIds(c, ids)).values());
        return MenuCatalog.of(routing.stations(), items);
    }

    /** TX1 (SDD 13): insert order, lines, history, deduct stock in ascending item id, flag, PLACED -> VALIDATED, history. */
    TxWork<Long> createWork(Order order) {
        List<OrderItem> byItemId = new ArrayList<>(order.getItems());
        byItemId.sort(Comparator.comparingLong(OrderItem::getItemId));
        return c -> {
            Instant now = clock.instant();
            long id = orders.insert(c, order);
            orders.insertItems(c, id, order.getItems());
            history.insert(c, new StatusHistoryEntry(id, null, OrderStatus.PLACED, now, SYSTEM, null));
            for (OrderItem line : byItemId) {
                if (!stock.deduct(c, line.getItemId(), line.getQuantity())) {
                    throw new InsufficientStockException(line.getItemId());
                }
            }
            if (!orders.markStockDeducted(c, id)) {
                throw new IllegalStateException("stock flag was already set on a new order");
            }
            if (!orders.transition(c, id, OrderStatus.PLACED, OrderStatus.VALIDATED, now)) {
                throw new IllegalStateException("a new order was not in PLACED");
            }
            history.insert(c, new StatusHistoryEntry(id, OrderStatus.PLACED, OrderStatus.VALIDATED, now, SYSTEM, null));
            return id;
        };
    }

    private SubmissionResult duplicateOf(Order order) {
        metrics.incrementRejected();
        events.log(EventType.ORDER_DUPLICATE);
        try {
            Optional<Long> existing = database.withConnection(c -> orders.findIdBySourceAndRef(c, order.getSource(), order.getExternalRef()));
            if (existing.isPresent()) {
                long id = existing.get();
                Optional<OrderState> state = database.withConnection(c -> orders.findState(c, id));
                if (state.isPresent()) {
                    return SubmissionResult.duplicate(id, state.get().status(), "PENDING".equals(state.get().compensationState()));
                }
            }
        } catch (CloudKitchenException e) {
            LOG.warn("Could not look up the existing order of a duplicate submission: {}", e.getCode());
        }
        return SubmissionResult.duplicateInFlight();
    }

    private SubmissionResult temporaryFailure(CloudKitchenException cause) {
        metrics.incrementRejected();
        if (SqlErrors.isTransient(cause)) {
            LOG.warn("Submission refused, the database is temporarily unavailable: {}", cause.getMessage());
        } else {
            LOG.error("Submission failed on a non-transient persistence error: {}", cause.getMessage());
        }
        events.log(EventType.ORDER_REJECTED, 0L, SubmissionResult.TEMPORARY_FAILURE);
        return SubmissionResult.rejected(SubmissionResult.TEMPORARY_FAILURE);
    }

    /** RR-1/RR-2: once the id is queued, nothing here may fail the order. */
    private void routeAfterOffer(long orderId) {
        try {
            transition(orderId, OrderStatus.VALIDATED, OrderStatus.ROUTED, SYSTEM, null);
        } catch (CloudKitchenException e) {
            LOG.warn("The ROUTED update of order {} failed after a successful enqueue; the worker will route it: {}", orderId, e.getMessage());
        }
    }

    private SubmissionResult afterQueueFull(long orderId, String reason) {
        CompensationOutcome outcome = compensate(orderId, reason);
        return switch (outcome) {
            case COMPENSATED -> SubmissionResult.queueFull(orderId, true);
            case PENDING -> SubmissionResult.queueFull(orderId, false);
            case INDETERMINATE -> SubmissionResult.indeterminate(orderId);
            case ALREADY_HANDLED -> {
                boolean pending = false;
                try {
                    pending = database.withConnection(c -> orders.findState(c, orderId)).map(s -> "PENDING".equals(s.compensationState())).orElse(false);
                } catch (CloudKitchenException e) {
                    LOG.warn("Could not re-read order {} after compensation: {}", orderId, e.getCode());
                }
                yield SubmissionResult.queueFull(orderId, !pending);
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------
    // Audited transitions (TX3), non-owner API
    // ------------------------------------------------------------------------------------------------------

    /**
     * The work of one status change, to be run inside a transaction (TXR-1/TXR-3): conditional update first; only if
     * it changed one row the history row, then, if {@link OrderStatus#isStockRestorableTo}, the guarded stock
     * restore. The result is {@code true} iff this call changed the row. Side effects are not part of the work.
     *
     * @throws com.cloudkitchen.exception.InvalidStatusTransitionException (checked) if the edge is not in the lifecycle
     */
    public TxWork<Boolean> transitionWork(long orderId, OrderStatus from, OrderStatus to, String actor, String note)
            throws CloudKitchenException {
        TxWork<TransitionResult> step = transitionStep(orderId, from, to, actor, note);
        return c -> step.run(c).applied();
    }

    TxWork<TransitionResult> transitionStep(long orderId, OrderStatus from, OrderStatus to, String actor, String note)
            throws CloudKitchenException {
        from.requireTransitionTo(to);
        return c -> {
            Instant now = clock.instant();
            if (!orders.transition(c, orderId, from, to, now)) {
                return TransitionResult.NOT_APPLIED;
            }
            history.insert(c, new StatusHistoryEntry(orderId, from, to, now, actor, note));
            return new TransitionResult(true, restoreWork(orderId, from, to).run(c));
        };
    }

    /**
     * The guarded stock restore (TXR-2): only for edges where {@link OrderStatus#isStockRestorableTo} holds, only for
     * the transaction that wins {@code claimStockRestore}, quantities added back in ascending item id. Must run inside
     * a transaction whose first statement already changed the order row (F-2).
     */
    private TxWork<Boolean> restoreWork(long orderId, OrderStatus from, OrderStatus to) {
        return c -> {
            if (!from.isStockRestorableTo(to) || !orders.claimStockRestore(c, orderId)) {
                return false;
            }
            for (OrderItem item : orders.findItems(c, orderId)) {
                stock.restore(c, item.getItemId(), item.getQuantity());
            }
            return true;
        };
    }

    /** One audited status change in its own transaction; metrics and events only when this call changed the row. */
    public boolean transition(long orderId, OrderStatus from, OrderStatus to, String actor, String note) throws CloudKitchenException {
        TransitionResult result = database.inTransaction(transitionStep(orderId, from, to, actor, note));
        afterCommit(orderId, to, note, result);
        return result.applied();
    }

    /** Fails an order from the status the caller last saw; {@code false} if somebody else moved it first. */
    public boolean failOrder(long orderId, OrderStatus expectedFrom, String actor, String reason) throws CloudKitchenException {
        return transition(orderId, expectedFrom, OrderStatus.FAILED, actor, reason);
    }

    /** {@link #cancelOrderDetailed} reduced to "was the order cancelled by this call". */
    public boolean cancelOrder(long orderId, String actor) throws CloudKitchenException {
        return cancelOrderDetailed(orderId, actor) == CancelOutcome.CANCELLED;
    }

    /**
     * RR-7: reads the current status and compensation marker on a fresh connection, refuses an order that is awaiting
     * compensation or past the point where cancelling is allowed, and otherwise performs the conditional transition;
     * a lost harmless race is retried (at most three rounds) instead of being reported as "not cancellable".
     */
    public CancelOutcome cancelOrderDetailed(long orderId, String actor) throws CloudKitchenException {
        for (int round = 0; round < 3; round++) {
            Optional<OrderState> state = database.withConnection(c -> orders.findState(c, orderId));
            if (state.isEmpty()) {
                return CancelOutcome.NOT_FOUND;
            }
            if ("PENDING".equals(state.get().compensationState())) {
                return CancelOutcome.AWAITING_COMPENSATION;
            }
            OrderStatus current = state.get().status();
            if (!current.canTransitionTo(OrderStatus.CANCELLED)) {
                return CancelOutcome.NOT_CANCELLABLE;
            }
            if (transition(orderId, current, OrderStatus.CANCELLED, actor, null)) {
                return CancelOutcome.CANCELLED;
            }
        }
        return CancelOutcome.CONTENDED;
    }

    private void afterCommit(long orderId, OrderStatus to, String note, TransitionResult result) {
        if (!result.applied()) {
            return; // I-5: nothing is emitted by a caller that did not change the row
        }
        EventType type = switch (to) {
            case PLACED -> EventType.ORDER_VALIDATED;
            case VALIDATED -> EventType.ORDER_VALIDATED;
            case ROUTED -> EventType.ORDER_ROUTED;
            case PREPARING -> EventType.ORDER_PREPARING;
            case PACKED -> EventType.ORDER_PACKED;
            case OUT_FOR_DELIVERY -> EventType.ORDER_OUT_FOR_DELIVERY;
            case DELIVERED -> EventType.ORDER_DELIVERED;
            case CANCELLED -> EventType.ORDER_CANCELLED;
            case FAILED -> isQueueReason(note) ? EventType.QUEUE_FULL : EventType.ORDER_FAILED;
        };
        if (note == null) {
            events.log(type, orderId);
        } else {
            events.log(type, orderId, note);
        }
        if (result.stockRestored()) {
            events.log(EventType.STOCK_RESTORED, orderId);
        }
        if (to == OrderStatus.FAILED) {
            metrics.incrementFailed();
        }
    }

    private static boolean isQueueReason(String note) {
        return "QUEUE_FULL".equals(note) || "INTERRUPTED".equals(note) || "STATION_UNAVAILABLE".equals(note) || "QUEUE_CLOSED".equals(note);
    }

    // ------------------------------------------------------------------------------------------------------
    // Durable compensation (SDD 7.10)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Called only when the dispatcher did <em>not</em> insert the id (RR-1, RR-11). TX4 is tried
     * {@code 1 + compensation.retry.max} times; if it cannot commit, the PENDING marker is committed (TXC1); if even
     * that fails the outcome is {@code INDETERMINATE} and the order simply continues as an ordinary order (DC-1).
     */
    public CompensationOutcome compensate(long orderId, String reason) {
        for (int attempt = 0; attempt <= settings.compensationRetryMax(); attempt++) {
            try {
                TransitionResult result = database.inTransaction(transitionStep(orderId, OrderStatus.VALIDATED, OrderStatus.FAILED, SYSTEM, reason));
                afterCommit(orderId, OrderStatus.FAILED, reason, result);
                return result.applied() ? CompensationOutcome.COMPENSATED : CompensationOutcome.ALREADY_HANDLED;
            } catch (CloudKitchenException e) {
                LOG.warn("Compensation of order {} failed on attempt {}: {}", orderId, attempt + 1, e.getMessage());
            }
        }
        try {
            Instant nextAt = clock.instant().plus(settings.compensationBackoff());
            boolean marked = database.inTransaction(c -> orders.markCompensationPending(c, orderId, reason, nextAt));
            if (marked) {
                events.log(EventType.COMPENSATION_PENDING, orderId, reason);
                return CompensationOutcome.PENDING;
            }
            return CompensationOutcome.ALREADY_HANDLED;
        } catch (CloudKitchenException e) {
            LOG.error("Order {} could neither be compensated nor marked pending: {}", orderId, e.getMessage());
            return CompensationOutcome.INDETERMINATE;
        }
    }

    /**
     * Completes one pending compensation (sweeper, startup recovery). {@code true} iff this call turned the order into
     * {@code FAILED} and restored its stock; on a failed attempt the backoff bookkeeping (TXC3) is recorded.
     */
    public boolean completePendingCompensation(long orderId) throws CloudKitchenException {
        Optional<OrderState> state = database.withConnection(c -> orders.findState(c, orderId));
        if (state.isEmpty() || state.get().status() != OrderStatus.VALIDATED || !"PENDING".equals(state.get().compensationState())) {
            return false;
        }
        String reason = state.get().compensationReason() == null ? "QUEUE_FULL" : state.get().compensationReason();
        try {
            TransitionResult result = database.inTransaction(transitionStep(orderId, OrderStatus.VALIDATED, OrderStatus.FAILED, SYSTEM, reason));
            afterCommit(orderId, OrderStatus.FAILED, reason, result);
            if (result.applied()) {
                events.log(EventType.COMPENSATION_COMPLETED, orderId);
            }
            return result.applied();
        } catch (CloudKitchenException e) {
            recordCompensationFailure(orderId, state.get().compensationAttempts());
            return false;
        }
    }

    /**
     * ADMIN-only operator retry (OP-1..OP-8): the standard TX4, ignoring the backoff but never the restore guard,
     * audited with actor {@code admin:<username>} and note {@code COMPENSATION_MANUAL}.
     *
     * @return {@code false} if the order is not pending any more (already resolved)
     */
    public boolean retryCompensation(long orderId, SessionUser admin) throws CloudKitchenException {
        if (admin == null || admin.role() != Role.ADMIN) {
            throw new CloudKitchenException("FORBIDDEN", "Only an administrator can retry a compensation.");
        }
        events.log(EventType.COMPENSATION_RETRY_REQUESTED, orderId, "userId=" + admin.userId());
        Optional<OrderState> state = database.withConnection(c -> orders.findState(c, orderId));
        if (state.isEmpty() || state.get().status() != OrderStatus.VALIDATED || !"PENDING".equals(state.get().compensationState())) {
            return false;
        }
        try {
            TransitionResult result = database.inTransaction(transitionStep(orderId, OrderStatus.VALIDATED, OrderStatus.FAILED,
                    "admin:" + admin.username(), "COMPENSATION_MANUAL"));
            afterCommit(orderId, OrderStatus.FAILED, "COMPENSATION_MANUAL", result);
            if (result.applied()) {
                events.log(EventType.COMPENSATION_COMPLETED, orderId);
            }
            return result.applied();
        } catch (CloudKitchenException e) {
            recordCompensationFailure(orderId, state.get().compensationAttempts());
            throw e;
        }
    }

    private void recordCompensationFailure(long orderId, int attemptsSoFar) {
        long factor = 1L << Math.min(Math.max(attemptsSoFar, 0), 20);
        Duration delay = settings.compensationBackoff().multipliedBy(factor);
        if (delay.compareTo(settings.compensationBackoffMax()) > 0) {
            delay = settings.compensationBackoffMax();
        }
        Instant nextAt = clock.instant().plus(delay);
        try {
            database.inTransaction(c -> {
                orders.recordCompensationFailure(c, orderId, nextAt);
                return null;
            });
        } catch (CloudKitchenException e) {
            LOG.warn("Could not record the failed compensation attempt of order {}: {}", orderId, e.getCode());
        }
        if (attemptsSoFar + 1 >= settings.compensationAlertAttempts()) {
            LOG.error("Compensation of order {} is stuck after {} attempts", orderId, attemptsSoFar + 1);
            events.log(EventType.COMPENSATION_STUCK, orderId, "attempts=" + (attemptsSoFar + 1));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Owner-fenced API: used only by StationWorker (SDD 7.11.12, F-6)
    // ------------------------------------------------------------------------------------------------------

    /** WM-1: takes the lease on a free, non-terminal, non-pending order; the new epoch is the fencing token. */
    public Optional<FencingToken> acquireLease(long orderId, String owner) throws CloudKitchenException {
        OptionalInt epoch = database.inTransaction(c -> orders.acquireLease(c, orderId, owner, settings.leaseTtl()));
        return epoch.isPresent() ? Optional.of(new FencingToken(orderId, owner, epoch.getAsInt())) : Optional.empty();
    }

    /** WM-2: extends the lease if it is still usable; never revives an expired, cleared or superseded one. */
    public boolean renewLease(FencingToken token) throws CloudKitchenException {
        Boolean renewed = withStampRetry(c -> orders.renewLease(c, token.orderId(), token.epoch(), token.owner(), settings.leaseTtl()));
        return Boolean.TRUE.equals(renewed);
    }

    /** WM-3: gives the lease up (no-op if it was superseded). */
    public void releaseLease(FencingToken token) throws CloudKitchenException {
        database.inTransaction(c -> orders.releaseLease(c, token.orderId(), token.epoch(), token.owner()));
    }

    /**
     * WM-4/WM-5: a step taken by the lease owner. The fenced conditional update is the first statement; with 0 rows
     * nothing else runs (F-2). A statement or commit slower than the stamp guard is rolled back and retried with a
     * fresh stamp (S-6).
     */
    public boolean transitionAsOwner(FencingToken token, OrderStatus from, OrderStatus to, String note) throws CloudKitchenException {
        from.requireTransitionTo(to);
        String actor = "worker:" + token.owner();
        TransitionResult result = withStampRetry(c -> {
            if (!orders.transition(c, token.orderId(), from, to, token, settings.leaseTtl())) {
                return TransitionResult.NOT_APPLIED;
            }
            history.insert(c, new StatusHistoryEntry(token.orderId(), from, to, clock.instant(), actor, note));
            return new TransitionResult(true, restoreWork(token.orderId(), from, to).run(c));
        });
        afterCommit(token.orderId(), to, note, result);
        return result.applied();
    }

    /** WM-6: permanent failure by the lease owner; stock is restored only if the status rule allows (inside the same transaction). */
    public boolean failAsOwner(FencingToken token, OrderStatus expectedFrom, String reason) throws CloudKitchenException {
        return transitionAsOwner(token, expectedFrom, OrderStatus.FAILED, reason);
    }

    private <T> T withStampRetry(TxWork<T> work) throws CloudKitchenException {
        for (int attempt = 0; ; attempt++) {
            try {
                return database.inTransaction(work);
            } catch (LeaseStampStaleException e) {
                if (attempt >= settings.leaseStampRetry()) {
                    throw e;
                }
                LOG.debug("Stale lease stamp, retrying with a fresh one ({} of {})", attempt + 1, settings.leaseStampRetry());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Infrastructure (monitor and recovery only)
    // ------------------------------------------------------------------------------------------------------

    /** Startup: no lease can be valid in a fresh process; epochs are kept so an old owner stays fenced out. */
    public int clearAllLeases() throws CloudKitchenException {
        return database.inTransaction(orders::clearAllLeases);
    }

    /** Ends the leases of one retired worker generation (exact owner string) and returns the affected order ids. */
    public List<Long> reclaimLeasesOf(String owner) throws CloudKitchenException {
        return database.inTransaction(c -> orders.expireLeasesOf(c, owner));
    }
}
