package com.cloudkitchen.snapshot;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.pipeline.NamedThreadFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the snapshot job every {@code snapshot.interval.seconds} (SDD 7.1 row 5, FR-RES-05).
 *
 * <p>Thread ownership: its own single-thread scheduler {@code snapshot-scheduler}, created in {@link #start()} and
 * ended in {@link #stop()}. {@code scheduleWithFixedDelay} means a run never overlaps the previous one (and
 * {@code SnapshotService} serialises writes anyway). Every run catches every exception: a failing run is counted and
 * logged by code or class name only, and the next interval tries again (SDD F-17); a run cut short by {@link #stop()} (its
 * thread is interrupted, which makes file channels fail by design) is neither counted nor logged as a failure; an uncaught exception would silently
 * cancel the schedule. The first run is one interval after {@link #start()}; the application starts the task after
 * recovery and stops it first in the shutdown, before the final snapshot.
 */
public final class SnapshotTask {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotTask.class);

    /** What one run does; production: write a snapshot and prune old ones. */
    @FunctionalInterface
    public interface Job {
        void run() throws CloudKitchenException;
    }

    /** Counters of the task; an immutable copy. */
    public record Stats(long runs, long failures, Instant lastSuccessAt, String lastFailure) {
    }

    private final Job job;
    private final long period;
    private final TimeUnit unit;
    private final Clock clock;
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Instant lastSuccessAt;
    private volatile String lastFailure;
    private ScheduledExecutorService scheduler;

    public SnapshotTask(Job job, int intervalSeconds, Clock clock) {
        this(job, intervalSeconds, TimeUnit.SECONDS, clock);
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("intervalSeconds must be at least 1");
        }
    }

    /** Package-private so that tests can use a period of a few milliseconds; the configuration allows whole seconds only. */
    SnapshotTask(Job job, long period, TimeUnit unit, Clock clock) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be positive");
        }
        this.job = Objects.requireNonNull(job, "job");
        this.period = period;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Schedules the runs; a second call does nothing. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("snapshot-scheduler", false));
        scheduler.scheduleWithFixedDelay(this::runOnce, period, period, unit);
    }

    /** Idempotent; interrupts a run in progress and waits briefly for the thread to end. */
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("The snapshot scheduler did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Stats stats() {
        return new Stats(runs.get(), failures.get(), lastSuccessAt, lastFailure);
    }

    /** One run; never throws. */
    void runOnce() {
        runs.incrementAndGet();
        try {
            job.run();
            lastSuccessAt = clock.instant();
        } catch (CloudKitchenException e) {
            if (Thread.currentThread().isInterrupted()) {
                // stop() interrupted the run: a file operation on an interrupted thread fails by design, this is no fault of the disk
                LOG.info("A snapshot run was cancelled by the shutdown");
                return;
            }
            failures.incrementAndGet();
            lastFailure = e.getCode();
            LOG.error("Snapshot run failed ({}); trying again at the next interval", e.getCode());
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            lastFailure = e.getClass().getSimpleName();
            LOG.error("Snapshot run failed ({}); trying again at the next interval", e.getClass().getName());
        }
    }
}
