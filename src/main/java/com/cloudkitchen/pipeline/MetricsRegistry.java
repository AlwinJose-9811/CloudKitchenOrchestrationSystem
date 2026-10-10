package com.cloudkitchen.pipeline;

import com.cloudkitchen.dto.MetricsView;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free counters for the dashboard (SDD 7.7). Each counter is an {@link AtomicLong}, so any thread may update it
 * without a lock. Values live in memory only and restart at zero with the application.
 */
public final class MetricsRegistry {

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong totalProcessingNanos = new AtomicLong();
    private final AtomicLong processingSamples = new AtomicLong();

    public void incrementSubmitted() {
        submitted.incrementAndGet();
    }

    public void incrementProcessed() {
        processed.incrementAndGet();
    }

    public void incrementFailed() {
        failed.incrementAndGet();
    }

    public void incrementRejected() {
        rejected.incrementAndGet();
    }

    /** Adds one sample: the time from the worker's claim to DELIVERED. Negative durations count as zero. */
    public void recordProcessingTime(Duration elapsed) {
        recordProcessingNanos(elapsed.toNanos());
    }

    /** Overload taking nanoseconds, for callers that already measure with {@code System.nanoTime()} differences. */
    public void recordProcessingNanos(long nanos) {
        totalProcessingNanos.addAndGet(Math.max(0, nanos));
        processingSamples.incrementAndGet();
    }

    /** An immutable copy of the counters; the average is 0 when there are no samples. */
    public MetricsView view() {
        long samples = processingSamples.get();
        long nanos = totalProcessingNanos.get();
        double average = samples == 0 ? 0.0 : nanos / (double) samples / 1_000_000.0;
        return new MetricsView(submitted.get(), processed.get(), failed.get(), rejected.get(), samples, average);
    }
}
