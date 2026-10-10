package com.cloudkitchen.pipeline;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.OrderProgress;
import com.cloudkitchen.dto.WorkerView;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.service.OrderService;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The consumer of one station's queue (SDD 7.3, 7.11). It takes order ids from the queue, reads the authoritative
 * state from the database, takes the order's lease and then drives the order through the lifecycle with owner-fenced
 * conditional transitions only. It never trusts a queued id, a hint set or a cached status: every step starts from a
 * fresh read and ends with a conditional update whose zero-row result means "somebody else owns this step".
 *
 * <p>Thread ownership: the instance is created by the orchestrator and run by exactly one pool thread; the volatile
 * fields are written by that thread and read by the monitor, the dashboard and tests. It holds no database
 * connection of its own: each service call borrows and returns a pooled one.
 */
public final class StationWorker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(StationWorker.class);
    private static final int SLICE_MS = 100;
    private static final int MAX_ROUNDS = 50;
    private static final int BACKOFF_CAP_MS = 5000;

    /** One database call that may fail transiently. */
    @FunctionalInterface
    private interface Call<T> {
        T run() throws CloudKitchenException;
    }

    /** The order cannot make progress now; release it and put the id back (RR-8). */
    private static final class ParkSignal extends Exception {
        private static final long serialVersionUID = 1L;

        ParkSignal() {
            super("park", null, false, false);
        }
    }

    /** This worker was retired or lost the order; it must not touch the order again. */
    private static final class StandDown extends Exception {
        private static final long serialVersionUID = 1L;

        StandDown() {
            super("stand down", null, false, false);
        }
    }

    /** Per-order working state; confined to the worker thread. */
    private static final class Session {
        final long id;
        FencingToken token;
        OrderStatus lastFrom;
        long claimNanos = -1;
        int reacquires;
        long leaseMillis; // when the lease was last extended by this worker (acquire, step or renewal), on the worker's clock
        boolean released;
        boolean abandoned;

        Session(long id) {
            this.id = id;
        }
    }

    private final StationRuntime runtime;
    private final int generation;
    private final String owner;
    private final OrderService service;
    private final PipelineSettings cfg;
    private final Clock clock;
    private final OrderEventLog events;
    private final MetricsRegistry metrics;
    private final AtomicReference<WorkerHook> hook;
    private final AtomicLong processed = new AtomicLong();

    private volatile WorkerState state = WorkerState.STARTING;
    private volatile long heartbeat;
    private volatile boolean stopRequested;
    private volatile boolean draining;
    private volatile boolean retired;
    private volatile boolean exited;
    private volatile long currentOrderId;
    private volatile int consecutiveFailures;

    StationWorker(StationRuntime runtime, int generation, OrderService service, PipelineSettings cfg, Clock clock,
                  OrderEventLog events, MetricsRegistry metrics, AtomicReference<WorkerHook> hook) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.generation = generation;
        this.owner = runtime.code() + "#" + generation;
        this.service = Objects.requireNonNull(service, "service");
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.hook = Objects.requireNonNull(hook, "hook");
        this.heartbeat = clock.millis();
    }

    // ------------------------------------------------------------------------------------------------------
    // Control and health (called by the orchestrator, the monitor and the dashboard)
    // ------------------------------------------------------------------------------------------------------

    int generation() {
        return generation;
    }

    String owner() {
        return owner;
    }

    /** Stops taking new ids at the next loop iteration. */
    void requestStop() {
        stopRequested = true;
    }

    /** Finishes what is queued, then exits. */
    void requestDrain() {
        draining = true;
    }

    /** Marks this generation as replaced: it checks the flag first in every iteration and never acquires again (L-6). */
    void retire() {
        retired = true;
    }

    boolean isRetired() {
        return retired;
    }

    boolean hasExited() {
        return exited;
    }

    public WorkerState state() {
        return state;
    }

    long processedCount() {
        return processed.get();
    }

    /** Alive and beating: not retired, not ended, and the last heartbeat is not older than {@code stallMs} (D1, D2). */
    boolean isHealthy(long nowMillis, long stallMs) {
        return !retired && !exited && nowMillis - heartbeat <= stallMs;
    }

    WorkerView view(long nowMillis, boolean dead, int restarts) {
        long current = currentOrderId;
        return new WorkerView(runtime.code(), dead ? WorkerState.DEAD : state, current == 0 ? null : current, processed.get(),
                consecutiveFailures, generation, restarts, Math.max(0, nowMillis - heartbeat), current == 0 ? null : current);
    }

    private void beat() {
        heartbeat = clock.millis();
    }

    private boolean mayAcquire() {
        return !retired && runtime.currentGeneration() == generation;
    }

    private void fire(WorkerHook.Stage stage, long orderId, OrderStatus step) {
        hook.get().at(stage, owner, orderId, step);
    }

    // ------------------------------------------------------------------------------------------------------
    // The loop (SDD 7.3)
    // ------------------------------------------------------------------------------------------------------

    @Override
    public void run() {
        String previousName = Thread.currentThread().getName();
        Thread.currentThread().setName(generation <= 1 ? "station-" + runtime.code() + "-worker"
                : "station-" + runtime.code() + "-worker-g" + generation);
        beat();
        try {
            loop();
        } finally {
            exited = true;
            if (state != WorkerState.DEAD) {
                state = WorkerState.STOPPED;
            }
            Thread.currentThread().setName(previousName);
        }
    }

    private void loop() {
        state = WorkerState.IDLE;
        while (!stopRequested && !retired && !Thread.currentThread().isInterrupted()) {
            beat();
            try {
                if (runtime.isPaused() && !draining) {
                    state = WorkerState.PAUSED;
                    Thread.sleep(SLICE_MS);
                    continue;
                }
                Long id = runtime.queue().poll(cfg.pollMs(), TimeUnit.MILLISECONDS);
                if (id == null) {
                    if (draining && runtime.queue().isEmpty()) {
                        break;
                    }
                    state = WorkerState.IDLE;
                    continue;
                }
                runtime.queuedIds().remove(id);
                if (retired) {
                    runtime.offerNow(id);
                    break;
                }
                handle(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        state = WorkerState.STOPPING;
    }

    private void handle(long id) throws InterruptedException {
        state = WorkerState.PROCESSING;
        currentOrderId = id;
        runtime.activeIds().put(id, (long) generation);
        try {
            fire(WorkerHook.Stage.AFTER_POLL, id, null);
            processOrder(id);
            consecutiveFailures = 0;
        } catch (VirtualMachineError e) {
            throw e; // a dying thread must not be hidden; the monitor replaces it
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            consecutiveFailures++;
            LOG.error("Worker {} could not handle order {}: {}", owner, id, t.getClass().getName());
        } finally {
            runtime.activeIds().remove(id, (long) generation);
            currentOrderId = 0;
            if (state == WorkerState.PROCESSING) {
                state = WorkerState.IDLE;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // One order
    // ------------------------------------------------------------------------------------------------------

    private void processOrder(long id) throws InterruptedException {
        Session s = new Session(id);
        try {
            if (admit(s)) {
                drive(s);
            }
        } catch (ParkSignal e) {
            park(s);
        } catch (StandDown e) {
            s.released = true; // somebody else owns the order now; only an epoch-fenced release would be allowed, and it is useless
        } catch (VirtualMachineError e) {
            s.abandoned = true; // a dying worker keeps its lease; the monitor reclaims it
            throw e;
        } catch (InterruptedException e) {
            throw e; // the finally block releases best effort, the order stays untouched in the database
        } catch (Throwable t) {
            failPermanently(s, t);
        } finally {
            releaseQuietly(s);
        }
    }

    /** Step 0 of SDD 7.3: skip terminal/pending orders, then take the lease. */
    private boolean admit(Session s) throws InterruptedException, ParkSignal, StandDown, CloudKitchenException {
        Optional<OrderProgress> read = retrying(() -> service.readProgress(s.id), s);
        if (read.isEmpty()) {
            LOG.warn("Worker {} was given unknown order {}", owner, s.id);
            return false;
        }
        OrderProgress p = read.get();
        if (p.status().isTerminal()) {
            events.log(EventType.ORDER_SKIPPED_TERMINAL, s.id);
            return false;
        }
        if (p.compensationPending()) {
            events.log(EventType.ORDER_SKIPPED_PENDING_COMPENSATION, s.id);
            return false;
        }
        if (!mayAcquire()) {
            runtime.offerNow(s.id);
            return false;
        }
        Optional<FencingToken> token = retrying(() -> service.acquireLease(s.id, owner), s);
        if (token.isEmpty()) {
            logWhySkipped(s);
            return false;
        }
        s.token = token.get();
        s.leaseMillis = clock.millis();
        fire(WorkerHook.Stage.AFTER_LEASE, s.id, null);
        if (!mayAcquire()) {
            runtime.offerNow(s.id); // retired while acquiring: the finally block releases, the next generation takes over
            return false;
        }
        return true;
    }

    /**
     * The lease was refused: say why. Between the admission read and the acquire the order may have become terminal or
     * pending, which the acquire predicate also refuses; otherwise another owner holds a valid lease.
     */
    private void logWhySkipped(Session s) throws InterruptedException, ParkSignal, StandDown, CloudKitchenException {
        Optional<OrderProgress> again = retrying(() -> service.readProgress(s.id), s);
        if (again.isPresent() && again.get().status().isTerminal()) {
            events.log(EventType.ORDER_SKIPPED_TERMINAL, s.id);
        } else if (again.isPresent() && again.get().compensationPending()) {
            events.log(EventType.ORDER_SKIPPED_PENDING_COMPENSATION, s.id);
        } else {
            events.log(EventType.ORDER_SKIPPED_LEASED, s.id);
        }
    }

    /** Steps 1-5 of SDD 7.3: a state machine driven by fresh reads and conditional steps. */
    private void drive(Session s) throws InterruptedException, ParkSignal, StandDown, CloudKitchenException {
        long startNanos = System.nanoTime();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            beat();
            Optional<OrderProgress> read = retrying(() -> service.readProgress(s.id), s);
            if (read.isEmpty()) {
                return;
            }
            OrderProgress p = read.get();
            if (p.status().isTerminal()) {
                events.log(EventType.ORDER_SKIPPED_TERMINAL, s.id);
                return;
            }
            if (p.compensationPending()) {
                events.log(EventType.ORDER_SKIPPED_PENDING_COMPENSATION, s.id);
                return;
            }
            if (p.claimEpoch() != s.token.epoch()) {
                events.log(EventType.LEASE_LOST, s.id);
                throw new StandDown();
            }
            if (s.lastFrom != null && p.status() == s.lastFrom) {
                reacquire(s);
            }
            s.lastFrom = null;
            switch (p.status()) {
                case VALIDATED -> step(s, OrderStatus.VALIDATED, OrderStatus.ROUTED, "ROUTED_BY_WORKER");
                case ROUTED -> {
                    if (step(s, OrderStatus.ROUTED, OrderStatus.PREPARING, null)) {
                        s.claimNanos = System.nanoTime();
                    }
                }
                case PREPARING -> {
                    pause(cfg.prepMs(), s);
                    step(s, OrderStatus.PREPARING, OrderStatus.PACKED, null);
                }
                case PACKED -> {
                    pause(cfg.packMs(), s);
                    step(s, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY, null);
                }
                case OUT_FOR_DELIVERY -> {
                    pause(cfg.deliveryMs(), s);
                    if (step(s, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, null)) {
                        s.released = true; // the terminal step cleared the lease
                        processed.incrementAndGet();
                        metrics.incrementProcessed();
                        metrics.recordProcessingNanos(System.nanoTime() - (s.claimNanos > 0 ? s.claimNanos : startNanos));
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
        }
        throw new ParkSignal();
    }

    /**
     * Rule L-5, case "my last step changed nothing and the order is unchanged": the lease expired or was cleared and
     * nobody superseded it, so a retired worker stops and a current one takes a new lease (new epoch, never a revival).
     */
    private void reacquire(Session s) throws InterruptedException, ParkSignal, StandDown, CloudKitchenException {
        if (!mayAcquire()) {
            runtime.offerNow(s.id);
            throw new StandDown();
        }
        if (s.reacquires >= cfg.reacquireMax()) {
            throw new ParkSignal();
        }
        Optional<FencingToken> token = retrying(() -> service.acquireLease(s.id, owner), s);
        if (token.isEmpty()) {
            events.log(EventType.LEASE_LOST, s.id);
            throw new StandDown();
        }
        s.token = token.get();
        s.leaseMillis = clock.millis();
        s.reacquires++;
        events.log(EventType.LEASE_REACQUIRED, s.id);
    }

    private boolean step(Session s, OrderStatus from, OrderStatus to, String note)
            throws InterruptedException, ParkSignal, StandDown, CloudKitchenException {
        fire(WorkerHook.Stage.BEFORE_STEP, s.id, from);
        boolean applied = retrying(() -> service.transitionAsOwner(s.token, from, to, note), s);
        fire(WorkerHook.Stage.AFTER_STEP, s.id, from);
        if (applied) {
            s.leaseMillis = clock.millis(); // a step also extends the lease
        } else {
            s.lastFrom = from;
        }
        return applied;
    }

    // ------------------------------------------------------------------------------------------------------
    // Failure handling (RR-8)
    // ------------------------------------------------------------------------------------------------------

    /** Runs a database call; transient failures are retried in place with capped backoff, then the order is parked. */
    private <T> T retrying(Call<T> call, Session s) throws InterruptedException, ParkSignal, StandDown, CloudKitchenException {
        int failures = 0;
        long backoff = cfg.retryBackoffMs();
        for (;;) {
            try {
                return call.run();
            } catch (CloudKitchenException e) {
                if (!OrderService.isTransientFailure(e)) {
                    throw e;
                }
                consecutiveFailures++;
                failures++;
                if (failures >= cfg.retryMax()) {
                    if (runtime.queue().remainingCapacity() > 0) {
                        throw new ParkSignal();
                    }
                    failures = 0; // the queue is full: keep retrying in place
                }
                LOG.warn("Worker {} retries order {} after a transient failure ({})", owner, s.id, e.getCode());
                pause((int) backoff, s);
                backoff = Math.min(backoff * 2, BACKOFF_CAP_MS);
            }
        }
    }

    /** Releases the lease and puts the id at the tail of the queue so other orders progress (RR-8). */
    private void park(Session s) throws InterruptedException {
        releaseQuietly(s);
        s.released = true;
        fire(WorkerHook.Stage.BEFORE_PARK_OFFER, s.id, null);
        events.log(EventType.ORDER_STALLED, s.id);
        LOG.error("Worker {} parked order {} after repeated transient failures", owner, s.id);
        if (!runtime.offerNow(s.id)) {
            LOG.warn("Queue of {} is full; order {} stays in the database for the next repair", runtime.code(), s.id);
        }
    }

    /** A permanent error: fail the order through the owner-fenced path, unless the lease is gone (the new owner decides). */
    private void failPermanently(Session s, Throwable cause) throws InterruptedException {
        LOG.error("Worker {} failed order {} permanently: {}", owner, s.id, cause.getClass().getName());
        if (s.token == null || s.released) {
            return;
        }
        try {
            Optional<OrderProgress> read = retrying(() -> service.readProgress(s.id), s);
            if (read.isEmpty() || read.get().status().isTerminal() || read.get().claimEpoch() != s.token.epoch()) {
                return;
            }
            OrderStatus from = read.get().status();
            if (!from.canTransitionTo(OrderStatus.FAILED)) {
                LOG.error("Order {} is {} and cannot be failed from there; it is left for the next repair", s.id, from);
                return;
            }
            if (retrying(() -> service.failAsOwner(s.token, from, "PROCESSING_ERROR"), s)) {
                s.released = true; // the terminal step cleared the lease
            }
        } catch (ParkSignal | StandDown | CloudKitchenException e) {
            LOG.error("Worker {} could not fail order {}; it stays non-terminal for the next repair", owner, s.id);
        }
    }

    private void releaseQuietly(Session s) {
        if (s.token == null || s.released || s.abandoned) {
            return;
        }
        s.released = true;
        try {
            service.releaseLease(s.token);
        } catch (CloudKitchenException | RuntimeException e) {
            LOG.debug("Worker {} could not release the lease of order {}; it will expire", owner, s.id);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Sleeping with a heartbeat and lease renewal
    // ------------------------------------------------------------------------------------------------------

    /**
     * Waits in slices so the heartbeat keeps beating and the lease is renewed (SDD 7.3 step 2, 7.11.3). A failed
     * renewal is not acted on here: the next fenced step returns zero rows and rule L-5 decides.
     */
    private void pause(int millis, Session s) throws InterruptedException, StandDown {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            if (retired) {
                throw new StandDown();
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            Thread.sleep(Math.min(SLICE_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
            beat();
            if (s.token != null && clock.millis() - s.leaseMillis >= cfg.leaseRenewMs()) {
                s.leaseMillis = clock.millis();
                renew(s);
            }
        }
        if (retired) {
            throw new StandDown();
        }
    }

    private void renew(Session s) {
        try {
            fire(WorkerHook.Stage.BEFORE_RENEW, s.id, null);
            if (!service.renewLease(s.token)) {
                LOG.debug("Worker {} could not renew the lease of order {}", owner, s.id);
            }
        } catch (CloudKitchenException e) {
            LOG.debug("Worker {} could not renew the lease of order {}: {}", owner, s.id, e.getCode());
        }
    }
}
