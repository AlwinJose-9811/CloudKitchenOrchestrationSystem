package com.cloudkitchen.pipeline;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.RecoverableOrder;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.InvalidStatusTransitionException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.service.OrderService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings the pipeline back after a start (SDD 7.10.6, RR-9, FR-RES-02/-09). The database is the only authority; the
 * station queues were lost with the previous process. The order of the steps matters and is fixed:
 * <ol>
 *   <li>the snapshot step (a hook until Phase 6) inserts orders missing from the database;</li>
 *   <li>all leases are cleared: no worker is running yet, so none can be valid (the fencing epoch is never reset, so
 *       an obsolete owner of an earlier run could still never write);</li>
 *   <li>every pending compensation gets one attempt round (the standard TX4);</li>
 *   <li>every remaining non-terminal order is enqueued, oldest id first; an order whose station has no worker is
 *       moved to {@code FAILED} with note {@code STATION_UNAVAILABLE} (stock restored only from {@code VALIDATED} or
 *       {@code ROUTED}). Terminal orders and orders still pending are never loaded.</li>
 * </ol>
 * Enqueuing is not a side effect (7.9.5): any number of duplicate entries, a repeated recovery or a worker that is
 * already running changes nothing, because every real effect needs a conditional update that changes one row. A
 * {@code VALIDATED} order is routed by the worker itself (RR-3); recovery never calls a transition for it.
 *
 * <p>Thread ownership: {@link #start} runs {@link #recover} on its own thread {@code recovery-loader}, which ends when
 * the work is done or when it is interrupted.
 */
public final class RecoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryService.class);
    private static final int ENQUEUE_WAIT_MS = 500;

    /** The Phase 6 hook: verify the newest snapshot and insert the orders it holds that the database lacks. */
    @FunctionalInterface
    public interface SnapshotStep {
        SnapshotStep NONE = () -> 0;

        /** @return the number of orders inserted */
        int run() throws CloudKitchenException;
    }

    /** What recovery did; an immutable copy. */
    public record Stats(boolean recovering, long enqueued, long failedStationUnavailable, long compensationsResolved,
                        long compensationsStillPending, long skippedUnavailable, long snapshotInserted) {
    }

    private final OrderService orders;
    private final OrderOrchestrator orchestrator;
    private final OrderEventLog events;
    private final SnapshotStep snapshot;
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong failedUnavailable = new AtomicLong();
    private final AtomicLong resolved = new AtomicLong();
    private final AtomicLong stillPending = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong inserted = new AtomicLong();

    private volatile boolean recovering = true;
    private Thread thread;

    public RecoveryService(OrderService orders, OrderOrchestrator orchestrator, OrderEventLog events, SnapshotStep snapshot) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.events = Objects.requireNonNull(events, "events");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** Runs {@link #recover} on the {@code recovery-loader} thread and runs {@code afterwards} when it finished normally. */
    public synchronized void start(Runnable afterwards) {
        if (thread != null) {
            throw new IllegalStateException("Recovery was already started");
        }
        thread = new Thread(() -> {
            try {
                recover();
                afterwards.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Recovery was interrupted; the remaining orders stay in the database for the next start");
            } catch (CloudKitchenException | RuntimeException e) {
                LOG.error("Recovery failed ({}); the application keeps refusing orders", e.getClass().getName());
            }
        }, "recovery-loader");
        thread.start();
    }

    /**
     * Interrupts a recovery that is still running and waits briefly for its thread to end, so that nothing it starts
     * can outlive the shutdown that called this (idempotent; restores the caller's interrupt flag).
     */
    public void stop() {
        Thread loader;
        synchronized (this) {
            loader = thread;
        }
        if (loader == null || !loader.isAlive() || loader == Thread.currentThread()) {
            return;
        }
        loader.interrupt();
        try {
            loader.join(5000);
            if (loader.isAlive()) {
                LOG.warn("The recovery thread did not end within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Blocks until recovery has finished (successfully or not); {@code false} on timeout. */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    public boolean isRecovering() {
        return recovering;
    }

    public Stats stats() {
        return new Stats(recovering, enqueued.get(), failedUnavailable.get(), resolved.get(), stillPending.get(), skipped.get(), inserted.get());
    }

    /** The four steps; safe to call again (a second run finds nothing new to do). */
    public void recover() throws CloudKitchenException, InterruptedException {
        try {
            events.log(EventType.RECOVERY_STARTED);
            inserted.addAndGet(snapshot.run());
            int cleared = orders.clearAllLeases();
            LOG.info("Recovery: {} stale leases cleared", cleared);
            resolvePendingCompensations();
            List<RecoverableOrder> candidates = orders.findRecoverableOrders();
            for (RecoverableOrder order : candidates) {
                if (Thread.currentThread().isInterrupted() || orchestrator.isStopping()) {
                    throw new InterruptedException("recovery stopped before every order was handed back");
                }
                reenqueueOrFail(order);
            }
            events.log(EventType.RECOVERY_COMPLETED, 0L, "orders=" + candidates.size());
            LOG.info("Recovery finished: {} enqueued, {} failed (station unavailable), {} compensations resolved",
                    enqueued.get(), failedUnavailable.get(), resolved.get());
        } finally {
            recovering = false;
            done.countDown();
        }
    }

    private void resolvePendingCompensations() throws CloudKitchenException {
        for (long id : orders.findPendingCompensationIds()) {
            if (orders.completePendingCompensation(id)) {
                resolved.incrementAndGet();
            } else {
                stillPending.incrementAndGet(); // the marker stays; the sweeper keeps retrying with backoff
            }
        }
    }

    /**
     * Hands one order back to its station queue, or fails it if the station has no worker (RR-9). Shared by the
     * sweeper. An order whose station is {@code DEAD} is left in the database for an operator restart: its worker
     * cannot take it, and failing it would turn an outage into a lost order.
     */
    void reenqueueOrFail(RecoverableOrder order) throws CloudKitchenException, InterruptedException {
        StationRuntime runtime = orchestrator.runtime(order.brandCode());
        if (runtime == null) {
            try {
                if (orders.failOrder(order.id(), order.status(), "SYSTEM", "STATION_UNAVAILABLE")) {
                    failedUnavailable.incrementAndGet();
                    LOG.error("Order {} was failed: its station is not running", order.id());
                }
            } catch (InvalidStatusTransitionException e) {
                skipped.incrementAndGet(); // for example PACKED: the lifecycle has no edge to FAILED, so the order stays as it is
                LOG.error("Order {} is {} and its station is not running; it cannot be failed from there", order.id(), order.status());
            }
            return;
        }
        if (order.status() == OrderStatus.PLACED) {
            return; // never committed by TX1 as PLACED; nothing a worker could do
        }
        while (!runtime.isDead() && !orchestrator.isStopping()) {
            if (runtime.tryEnqueue(order.id(), ENQUEUE_WAIT_MS)) {
                enqueued.incrementAndGet();
                events.log(EventType.ORDER_REENQUEUED, order.id(), order.status().name());
                return;
            }
            // the queue is saturated: the worker drains it, so wait again; the loop ends if the station dies or we stop
        }
        skipped.incrementAndGet();
        LOG.warn("Order {} was not queued: station {} is {}", order.id(), order.brandCode(), runtime.isDead() ? "DEAD" : "stopping");
    }
}
