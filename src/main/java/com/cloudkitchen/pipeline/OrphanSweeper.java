package com.cloudkitchen.pipeline;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dto.RecoverableOrder;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.service.OrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The coarse backstop behind the worker monitor (SDD 7.9, FR-RES-06). Each pass (1) runs the same repair routine as the
 * monitor, so a dead or hung worker and a lost queue id are recovered even if the monitor is dead; (2) completes
 * pending compensations whose backoff has elapsed; (3) hands genuine orphans back to their station queue. An order is
 * an orphan only if it is non-terminal, untouched for {@code recovery.orphan.after.seconds}, has no valid database
 * lease, is not awaiting compensation, is not in the physical queue of a healthy worker and still satisfies all of that
 * when re-read just before the enqueue. The sweeper changes no order state of its own: it enqueues ids and calls the
 * standard compensation and station-unavailable transitions.
 *
 * <p>Thread ownership: its own single-thread scheduler {@code reconcile-scheduler}; passes never overlap
 * ({@code scheduleWithFixedDelay}) and every pass catches all exceptions.
 */
public final class OrphanSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanSweeper.class);

    /** The sweeper's tunables. */
    public record Settings(boolean enabled, int initialDelaySeconds, int intervalSeconds, int orphanAfterSeconds, int batch) {
        public static Settings from(AppConfig c) {
            return new Settings(c.recoverySweepEnabled(), c.recoverySweepInitialDelaySeconds(), c.recoverySweepIntervalSeconds(),
                    c.recoveryOrphanAfterSeconds(), c.recoverySweepBatch());
        }
    }

    /** What the sweeper did; separate from the five locked metrics. */
    public record Stats(long passes, long reenqueued, long compensationsCompleted, Instant lastPassAt) {
    }

    private final OrderService orders;
    private final OrderOrchestrator orchestrator;
    private final RecoveryService recovery;
    private final Settings settings;
    private final Clock clock;
    private final OrderEventLog events;
    private final AtomicLong passes = new AtomicLong();
    private final AtomicLong reenqueued = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    private volatile Instant lastPassAt;
    private ScheduledExecutorService scheduler;

    public OrphanSweeper(OrderService orders, OrderOrchestrator orchestrator, RecoveryService recovery, Settings settings,
                         Clock clock, OrderEventLog events) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Schedules the passes after the initial delay; does nothing if disabled or already started. */
    public synchronized void start() {
        if (!settings.enabled() || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("reconcile-scheduler", false));
        scheduler.scheduleWithFixedDelay(this::runOnce, settings.initialDelaySeconds(), settings.intervalSeconds(), TimeUnit.SECONDS);
    }

    /** Idempotent; interrupts a running pass and waits briefly for the thread to end. */
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("The orphan sweeper did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Stats stats() {
        return new Stats(passes.get(), reenqueued.get(), completed.get(), lastPassAt);
    }

    /** One pass (SDD 7.9.3). Never throws. */
    void runOnce() {
        if (!settings.enabled() || !orders.isAccepting() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            orchestrator.repairAll();
            completeDueCompensations();
            reenqueueOrphans();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CloudKitchenException | RuntimeException e) {
            LOG.error("Sweeper pass failed: {}", e.getClass().getName());
        } finally {
            passes.incrementAndGet();
            lastPassAt = clock.instant();
        }
    }

    /** Step 1 of a pass. */
    void completeDueCompensations() throws CloudKitchenException {
        for (long id : orders.findDuePendingCompensationIds(settings.batch())) {
            if (orders.completePendingCompensation(id)) {
                completed.incrementAndGet();
            }
        }
    }

    /** Step 3 of a pass (package-visible so that tests can exercise it without the shared repair step). */
    void reenqueueOrphans() throws CloudKitchenException, InterruptedException {
        Duration threshold = Duration.ofSeconds(settings.orphanAfterSeconds());
        Set<String> fullStations = new HashSet<>();
        long handedBack = 0;
        for (RecoverableOrder candidate : orders.findStaleOrders(threshold, settings.batch())) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            StationRuntime runtime = orchestrator.runtime(candidate.brandCode());
            if (runtime == null) {
                recovery.reenqueueOrFail(candidate);
                continue;
            }
            if (fullStations.contains(candidate.brandCode()) || orchestrator.queuedOnHealthyWorker(runtime, candidate.id())
                    || !orders.isStale(candidate.id(), threshold)) {
                continue;
            }
            if (runtime.offerNow(candidate.id())) {
                handedBack++;
                reenqueued.incrementAndGet();
                events.log(EventType.ORDER_REENQUEUED, candidate.id(), candidate.status().name());
            } else {
                fullStations.add(candidate.brandCode()); // the queue is full: stop for this station until the next pass
            }
        }
        if (handedBack > 0) {
            LOG.info("Sweeper handed {} orphaned orders back to their stations", handedBack);
        }
    }
}
