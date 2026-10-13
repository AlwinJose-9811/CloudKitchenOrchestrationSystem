package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dto.MetricsView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {

    private final MetricsRegistry metrics = new MetricsRegistry();

    @Test
    void everythingStartsAtZeroAndTheAverageWithNoSamplesIsZero() {
        MetricsView view = metrics.view();
        assertEquals(new MetricsView(0, 0, 0, 0, 0, 0.0), view);
    }

    @Test
    void eachCounterCountsOnlyItsOwnEvents() {
        metrics.incrementSubmitted();
        metrics.incrementSubmitted();
        metrics.incrementSubmitted();
        metrics.incrementRejected();
        metrics.incrementFailed();
        metrics.incrementFailed();
        metrics.incrementProcessed();
        MetricsView view = metrics.view();
        assertEquals(3, view.submitted());
        assertEquals(1, view.rejected());
        assertEquals(2, view.failed());
        assertEquals(1, view.processed());
    }

    @Test
    void averageProcessingTimeIsTotalOverSamplesInMilliseconds() {
        metrics.recordProcessingTime(Duration.ofMillis(100));
        metrics.recordProcessingTime(Duration.ofMillis(300));
        MetricsView view = metrics.view();
        assertEquals(2, view.processingSamples());
        assertEquals(200.0, view.averageProcessingMillis(), 1e-9);
    }

    @Test
    void theNanosecondOverloadAgreesWithTheDurationOne() {
        metrics.recordProcessingNanos(1_500_000);
        assertEquals(1.5, metrics.view().averageProcessingMillis(), 1e-9);
    }

    @Test
    void aNegativeSampleCountsAsZeroButStillAsASample() {
        metrics.recordProcessingNanos(-5);
        metrics.recordProcessingTime(Duration.ofMillis(10));
        MetricsView view = metrics.view();
        assertEquals(2, view.processingSamples());
        assertEquals(5.0, view.averageProcessingMillis(), 1e-9);
    }

    @Test
    void aViewIsASnapshotAndDoesNotChangeLater() {
        MetricsView before = metrics.view();
        metrics.incrementSubmitted();
        MetricsView after = metrics.view();
        assertEquals(0, before.submitted());
        assertEquals(1, after.submitted());
        assertNotSame(before, after);
    }

    @RepeatedTest(20)
    void concurrentUpdatesAreNeverLost() throws Exception {
        int threads = 8;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "metrics-test"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int n = 0; n < perThread; n++) {
                        metrics.incrementSubmitted();
                        metrics.incrementProcessed();
                        metrics.incrementFailed();
                        metrics.incrementRejected();
                        metrics.recordProcessingNanos(2_000_000);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            long expected = (long) threads * perThread;
            MetricsView view = metrics.view();
            assertEquals(expected, view.submitted());
            assertEquals(expected, view.processed());
            assertEquals(expected, view.failed());
            assertEquals(expected, view.rejected());
            assertEquals(expected, view.processingSamples());
            assertEquals(2.0, view.averageProcessingMillis(), 1e-9);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
