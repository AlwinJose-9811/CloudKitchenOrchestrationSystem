package com.cloudkitchen.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic health check of the station workers (SDD 7.11.4). Every pass asks the orchestrator to repair each station:
 * replace a dead or stalled worker, reclaim its leases and re-offer lost ids. All of the logic lives in
 * {@link OrderOrchestrator#repairAll()} so that a second scheduler can run exactly the same routine; this class only
 * owns the thread (named {@code worker-monitor}) and catches every exception, because an exception escaping a
 * scheduled task would silently cancel all later runs.
 */
public final class WorkerMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMonitor.class);

    private final OrderOrchestrator orchestrator;
    private final Clock clock;
    private final int intervalSeconds;
    private volatile ScheduledExecutorService scheduler;
    private volatile Instant lastPass;

    WorkerMonitor(OrderOrchestrator orchestrator, Clock clock, int intervalSeconds) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.intervalSeconds = intervalSeconds;
    }

    /** Schedules the passes; starting twice does nothing. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ScheduledExecutorService next = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("worker-monitor", false));
        next.scheduleWithFixedDelay(this::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        scheduler = next;
    }

    /** One pass; never throws. */
    public void runOnce() {
        try {
            orchestrator.repairAll();
        } catch (RuntimeException e) {
            LOG.error("Worker monitor pass failed: {}", e.getClass().getName());
        } finally {
            lastPass = clock.instant();
        }
    }

    /** When the last pass ended, or {@code null} if none ran (shown on the dashboard). */
    public Instant lastPass() {
        return lastPass;
    }

    /** Stops the scheduler and waits briefly for a running pass; restores the interrupt flag if interrupted. */
    public synchronized void stop() {
        ScheduledExecutorService current = scheduler;
        if (current == null) {
            return;
        }
        scheduler = null;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("Worker monitor did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
