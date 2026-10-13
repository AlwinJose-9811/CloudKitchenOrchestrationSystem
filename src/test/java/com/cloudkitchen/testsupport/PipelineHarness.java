package com.cloudkitchen.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.pipeline.OrderOrchestrator;
import com.cloudkitchen.pipeline.PipelineSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real service over the real embedded database, wired to the real {@link OrderOrchestrator} (workers included),
 * plus the invariant checkers every pipeline test uses: {@link #assertStockLedger()}, {@link #assertEachEdgeOnce},
 * thread and connection leak checks. Waiting is done on the event log's signal, never with a sleep.
 */
public final class PipelineHarness implements AutoCloseable {

    public static final Duration WAIT = Duration.ofSeconds(20);

    public final ServiceHarness h;
    public final OrderOrchestrator orch;
    public final PipelineSettings settings;
    private Map<Long, Integer> initialStock = new HashMap<>();
    private boolean stopped;

    private PipelineHarness(ServiceHarness h, PipelineSettings settings, java.time.Clock workerClock) {
        this.h = h;
        this.settings = settings;
        this.orch = new OrderOrchestrator(h.routing, settings, workerClock, h.events, h.metrics);
    }

    /** Short delays, small queues, fast polling: a settings baseline tests adjust with the {@code with...} methods. */
    public static PipelineSettings fast() {
        return new PipelineSettings(50, 200, 0, 0, 0, 5, 5, 10_000, 3, 20, 60_000, 5, 300, 10, 200, 30);
    }

    /** Resets the database, starts the orchestrator (without the scheduled monitor) and routes submissions to it. */
    public static PipelineHarness start(ServiceHarness h, PipelineSettings settings) throws SQLException, CloudKitchenException {
        return start(h, settings, h.clock, h.service);
    }

    /**
     * As {@link #start(ServiceHarness, PipelineSettings)} with a separate clock for the workers' heartbeat and renewal
     * timing (the database stamps keep using the harness clock) and a separate service for the workers to call.
     */
    public static PipelineHarness start(ServiceHarness h, PipelineSettings settings, java.time.Clock workerClock,
                                        com.cloudkitchen.service.OrderService workerService) throws SQLException, CloudKitchenException {
        h.reset();
        PipelineHarness p = new PipelineHarness(h, settings, workerClock);
        h.dispatcher.use(p.orch);
        p.orch.start(workerService, false);
        p.snapshotStock();
        return p;
    }

    /**
     * Simulates an abrupt stop of the process: the workers are stopped without draining (sleeping workers are
     * interrupted), the queues and every in-memory set are dropped with the orchestrator, and whatever the database
     * holds at that moment (statuses, leases, markers) stays exactly as it was.
     */
    public void crash() throws InterruptedException {
        if (stopped) {
            return;
        }
        stopped = true;
        List<Thread> threads = stationThreads();
        orch.shutdownGracefully(Duration.ZERO, Duration.ofMillis(100));
        for (Thread t : threads) {
            t.join(5000);
        }
        h.dispatcher.use(new FakeDispatchers.Accepting());
    }

    /**
     * A new process on the same database: a fresh orchestrator with fresh queues and workers (no reset, no recovery;
     * the test runs {@link com.cloudkitchen.pipeline.RecoveryService} itself). The stock ledger keeps the starting
     * point of this harness so that it spans the restart.
     */
    public PipelineHarness restart(PipelineSettings next) throws SQLException, CloudKitchenException {
        PipelineHarness p = new PipelineHarness(h, next, h.clock);
        p.initialStock = initialStock;
        h.dispatcher.use(p.orch);
        p.orch.start(h.service, false);
        return p;
    }

    /** A worker held at one hook stage: {@code blocked} opens when it arrives, it continues when {@code release} opens. */
    public static final class Held {
        public final java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        public final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        public void awaitBlocked() throws InterruptedException {
            assertTrue(blocked.await(20, java.util.concurrent.TimeUnit.SECONDS), "the worker never reached the hold point");
        }
    }

    /**
     * Holds the first worker of {@code code} that reaches {@code stage} (for the order given, or any order if
     * {@code orderId} is null; for the status given, or any if {@code step} is null) until the test releases it.
     * The hold gives up after 30 seconds so a broken test cannot hang the build.
     */
    public Held holdAt(String code, com.cloudkitchen.pipeline.WorkerHook.Stage stage, Long orderId, OrderStatus step) {
        Held held = new Held();
        orch.setWorkerHook((s, owner, id, st) -> {
            if (s == stage && owner.startsWith(code + "#") && (orderId == null || orderId == id) && (step == null || step == st)
                    && held.blocked.getCount() == 1) {
                held.blocked.countDown();
                try {
                    held.release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return held;
    }

    /** The id of the newest order whose TX1 has committed (taken from the event log). */
    public long lastValidatedId() {
        return h.events.all().stream().filter(e -> e.type() == EventType.ORDER_VALIDATED).reduce((a, b) -> b).orElseThrow().orderId();
    }

    /** An {@code OrderService} like the harness one (same DAOs, metrics and event log) over another data source. */
    public static com.cloudkitchen.service.OrderService serviceOver(ServiceHarness h, javax.sql.DataSource dataSource) {
        com.cloudkitchen.db.Database database = new com.cloudkitchen.db.Database(dataSource, h.clock, 3, 1);
        com.cloudkitchen.service.OrderService service = new com.cloudkitchen.service.OrderService(database, h.t.orders, h.t.stock,
                h.t.history, h.t.menu, new com.cloudkitchen.service.OrderValidator(), h.routing, new FakeDispatchers.Accepting(),
                new com.cloudkitchen.pipeline.InFlightRegistry(), h.metrics, h.events, h.clock,
                com.cloudkitchen.service.SubmissionHook.NO_OP, com.cloudkitchen.service.OrderService.Settings.from(h.db.config()));
        service.setAccepting(true);
        return service;
    }

    // ---- orders created directly in the database (no stock deduction), for resume and skip tests ----

    /** Persists a fresh Biryani order and walks it to {@code target} along the shortest lifecycle path. */
    public long persistedAt(String ref, OrderStatus target) throws CloudKitchenException {
        List<OrderStatus> path = switch (target) {
            case PLACED -> List.of();
            case VALIDATED -> List.of(OrderStatus.VALIDATED);
            case ROUTED -> List.of(OrderStatus.VALIDATED, OrderStatus.ROUTED);
            case PREPARING -> List.of(OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING);
            case PACKED -> List.of(OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING, OrderStatus.PACKED);
            case OUT_FOR_DELIVERY -> List.of(OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING, OrderStatus.PACKED,
                    OrderStatus.OUT_FOR_DELIVERY);
            case DELIVERED -> List.of(OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING, OrderStatus.PACKED,
                    OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);
            case CANCELLED -> List.of(OrderStatus.VALIDATED, OrderStatus.CANCELLED);
            case FAILED -> List.of(OrderStatus.VALIDATED, OrderStatus.FAILED);
        };
        return h.t.persistAt(h.t.biryaniOrder(ref), path.toArray(new OrderStatus[0]));
    }

    /**
     * Starts the orchestrator on the data that is already in the database (no reset), for tests that created orders
     * first, with the given starting stock for the ledger (read it with {@link #readStock} before creating the orders).
     */
    public static PipelineHarness attach(ServiceHarness h, PipelineSettings settings, Map<Long, Integer> startingStock)
            throws SQLException, CloudKitchenException {
        PipelineHarness p = new PipelineHarness(h, settings, h.clock);
        p.initialStock = startingStock;
        h.dispatcher.use(p.orch);
        p.orch.start(h.service, false);
        return p;
    }

    /** A committed {@code VALIDATED} order with its stock held and nothing queued (the submitter "died" after TX1). */
    public long validatedWithStock(String ref) throws CloudKitchenException {
        h.hook.set(stage -> {
            if (stage == com.cloudkitchen.service.SubmissionHook.Stage.AFTER_TX1) {
                throw new SimulatedCrash("after TX1");
            }
        });
        try {
            org.junit.jupiter.api.Assertions.assertThrows(SimulatedCrash.class, () -> submit(ref));
        } finally {
            h.hook.set(com.cloudkitchen.service.SubmissionHook.NO_OP);
        }
        return lastValidatedId();
    }

    /**
     * A committed order with its stock held, moved to {@code target} by plain transitions (no worker involved).
     * Uses whatever dispatcher is installed, so call it while the fake accepting dispatcher is active.
     */
    public long heldAt(String ref, OrderStatus target) throws CloudKitchenException {
        if (target == OrderStatus.VALIDATED) {
            return validatedWithStock(ref);
        }
        long id = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 1, "Raita", 1)).orderId();
        OrderStatus[] path = {OrderStatus.PREPARING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY};
        OrderStatus current = OrderStatus.ROUTED;
        for (OrderStatus next : path) {
            if (current == target) {
                break;
            }
            h.service.transition(id, current, next, "TEST", null);
            current = next;
        }
        return id;
    }

    /** Down-stream failure used by the compensation tests: the restore statement fails like a lost connection. */
    public static java.sql.SQLTransientConnectionException outage() {
        return new java.sql.SQLTransientConnectionException("outage", "08S01", 0);
    }

    /**
     * An order whose queue-full compensation failed: {@code VALIDATED} with a {@code PENDING} marker (attempts 1, due
     * after the configured backoff), its stock still held. Faults are cleared and the dispatcher is the orchestrator again.
     */
    public long pendingOrder(String ref) throws CloudKitchenException {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull(com.cloudkitchen.exception.QueueFullException.Reason.QUEUE_FULL));
        h.stockFaults.failAlways("restore", outage());
        try {
            SubmissionResult r = submit(ref);
            assertEquals(SubmissionResult.Outcome.QUEUE_FULL, r.outcome());
            assertFalse(r.compensated());
            return r.orderId();
        } finally {
            h.stockFaults.clearFaults();
            h.dispatcher.use(orch);
        }
    }

    // ---- submissions ----

    public SubmissionResult submit(OrderSource source, String ref, String brand, Object... items) throws CloudKitchenException {
        return h.service.submitOrder(h.t.request(source, ref, brand, items));
    }

    /** One Chicken Biryani + one Raita from Swiggy. */
    public SubmissionResult submit(String ref) throws CloudKitchenException {
        return submit(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 1, "Raita", 1);
    }

    public long accepted(String ref) throws CloudKitchenException {
        SubmissionResult r = submit(ref);
        assertEquals(SubmissionResult.Outcome.ACCEPTED, r.outcome(), "submission " + ref);
        return r.orderId();
    }

    // ---- waiting ----

    /** Waits for the order's terminal event and returns it. */
    public EventType awaitTerminal(long id) throws InterruptedException {
        EventType[] terminal = {EventType.ORDER_DELIVERED, EventType.ORDER_FAILED, EventType.QUEUE_FULL, EventType.ORDER_CANCELLED};
        boolean reached = h.events.awaitUntil(WAIT, () -> {
            for (EventType type : terminal) {
                if (h.events.countFor(type, id) > 0) {
                    return true;
                }
            }
            return false;
        });
        if (!reached) {
            throw new AssertionError("order " + id + " reached no terminal state within " + WAIT + "; status " + status(id));
        }
        for (EventType type : terminal) {
            if (h.events.countFor(type, id) > 0) {
                return type;
            }
        }
        throw new AssertionError("unreachable");
    }

    public void awaitDelivered(long id) throws InterruptedException {
        assertEquals(EventType.ORDER_DELIVERED, awaitTerminal(id), "order " + id + " final state " + status(id));
    }

    /**
     * Bounded polling of in-memory state that has no event to wait on (a worker's state, a queue size). It parks for
     * microseconds, never sleeps for a fixed time, and is used to wait for a database effect only where that effect emits
     * no event (a lost reply).
     */
    public static boolean awaitCondition(Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(200_000);
        }
        return true;
    }

    /**
     * Moves the shared fake clock forward and waits until every worker not listed in {@code staleCodes} has beaten its
     * heartbeat again on the new time. Idle workers beat every poll (20 ms), so without this wait a monitor pass right
     * after the jump would see them as stalled; the listed workers are the ones the test wants to look stalled.
     */
    public void advance(Duration amount, String... staleCodes) {
        h.clock.advance(amount);
        Set<String> stale = Set.of(staleCodes);
        assertTrue(awaitCondition(Duration.ofSeconds(10), () -> orch.stationViews().stream()
                .filter(v -> !stale.contains(v.stationCode()))
                .allMatch(v -> v.worker().heartbeatAgeMillis() < 1000)), "idle workers did not beat on the new time");
    }

    /** The state of the worker of one station, from the dashboard view. */
    public com.cloudkitchen.domain.enums.WorkerState workerState(String code) {
        return orch.stationViews().stream().filter(v -> v.stationCode().equals(code)).findFirst().orElseThrow().worker().state();
    }

    // ---- database reads ----

    public String status(long id) {
        try {
            return h.t.text("SELECT status FROM orders WHERE id = ?", id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> edges(long id) throws SQLException {
        List<String> edges = new ArrayList<>();
        try (Connection c = h.db.connection();
             PreparedStatement p = c.prepareStatement("SELECT from_status, to_status FROM order_status_history WHERE order_id = ? ORDER BY id")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    edges.add(rs.getString(1) + "->" + rs.getString(2));
                }
            }
        }
        return edges;
    }

    public List<String> actors(long id) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection c = h.db.connection();
             PreparedStatement p = c.prepareStatement("SELECT actor FROM order_status_history WHERE order_id = ? ORDER BY id")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    actors.add(rs.getString(1));
                }
            }
        }
        return actors;
    }

    public long count(String sql, Object... params) throws SQLException {
        return h.t.number(sql, params);
    }

    // ---- invariant checkers ----

    /** Every (from, to) edge occurs once and the edges form one unbroken chain from {@code null -> PLACED}. */
    public void assertEachEdgeOnce(long id) throws SQLException {
        assertEachEdgeOnce(h, id);
    }

    public static void assertEachEdgeOnce(ServiceHarness h, long id) throws SQLException {
        List<String> edges = new ArrayList<>();
        try (Connection c = h.db.connection();
             PreparedStatement p = c.prepareStatement("SELECT from_status, to_status FROM order_status_history WHERE order_id = ? ORDER BY id")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    edges.add(rs.getString(1) + "->" + rs.getString(2));
                }
            }
        }
        assertEquals(edges.size(), Set.copyOf(edges).size(), "repeated edge in " + edges);
        assertEquals("null->PLACED", edges.get(0));
        String previousTo = "PLACED";
        for (String edge : edges.subList(1, edges.size())) {
            String[] parts = edge.split("->");
            assertEquals(previousTo, parts[0], "chain broken in " + edges);
            previousTo = parts[1];
        }
        assertEquals(h.t.text("SELECT status FROM orders WHERE id = ?", id), previousTo, "last edge does not lead to the stored status");
    }

    /** Remembers the current stock as the starting point of {@link #assertStockLedger()}. */
    public void snapshotStock() throws SQLException {
        initialStock = readStock(h);
    }

    public static Map<Long, Integer> readStock(ServiceHarness h) throws SQLException {
        Map<Long, Integer> stock = new HashMap<>();
        try (Connection c = h.db.connection(); PreparedStatement p = c.prepareStatement("SELECT item_id, stock_qty FROM inventory");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                stock.put(rs.getLong(1), rs.getInt(2));
            }
        }
        return stock;
    }

    /**
     * The stock ledger: no item is negative, and every item's stock equals its starting stock minus the quantities of
     * all orders whose {@code stock_deducted} flag is still set. Exactly-once deduction and restore both follow.
     */
    public void assertStockLedger() throws SQLException {
        assertStockLedger(h, initialStock);
    }

    public static void assertStockLedger(ServiceHarness h, Map<Long, Integer> initial) throws SQLException {
        Map<Long, Integer> now = readStock(h);
        Map<Long, Integer> held = new HashMap<>();
        try (Connection c = h.db.connection();
             PreparedStatement p = c.prepareStatement("SELECT oi.item_id, SUM(oi.quantity) FROM order_items oi JOIN orders o ON o.id = oi.order_id"
                     + " WHERE o.stock_deducted = 1 GROUP BY oi.item_id");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                held.put(rs.getLong(1), rs.getInt(2));
            }
        }
        for (Map.Entry<Long, Integer> e : initial.entrySet()) {
            int actual = now.get(e.getKey());
            assertTrue(actual >= 0, "item " + e.getKey() + " is negative: " + actual);
            assertEquals(e.getValue() - held.getOrDefault(e.getKey(), 0), actual, "ledger of item " + e.getKey());
        }
    }

    // ---- lifecycle and leak checks ----

    /** Live station worker threads. */
    public static List<Thread> stationThreads() {
        List<Thread> found = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("station-") && t.isAlive()) {
                found.add(t);
            }
        }
        return found;
    }

    /** Stops the orchestrator, joins every worker thread and checks that no pooled connection is still lent out. */
    public void stop() throws InterruptedException {
        if (stopped) {
            return;
        }
        stopped = true;
        List<Thread> threads = stationThreads();
        assertTrue(orch.shutdownGracefully(Duration.ofSeconds(5), Duration.ofSeconds(5)), "workers did not stop");
        for (Thread t : threads) {
            t.join(5000);
            assertFalse(t.isAlive(), "thread " + t.getName() + " leaked");
        }
        assertEquals(0, h.db.database().activeConnections(), "pooled connections still lent out");
        h.dispatcher.use(new FakeDispatchers.Accepting());
    }

    public void assertNoStationThreads() {
        assertTrue(stationThreads().isEmpty(), "station threads alive: " + stationThreads());
    }

    @Override
    public void close() {
        try {
            stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
