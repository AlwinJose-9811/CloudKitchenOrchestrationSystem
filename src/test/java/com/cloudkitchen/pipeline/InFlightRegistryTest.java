package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class InFlightRegistryTest {

    private final InFlightRegistry registry = new InFlightRegistry();

    @Test
    void theSecondAcquireOfTheSameKeyFailsUntilReleased() {
        assertTrue(registry.tryAcquire(OrderSource.SWIGGY, "SW-1"));
        assertFalse(registry.tryAcquire(OrderSource.SWIGGY, "SW-1"));
        assertTrue(registry.isInFlight(OrderSource.SWIGGY, "SW-1"));
        registry.release(OrderSource.SWIGGY, "SW-1");
        assertFalse(registry.isInFlight(OrderSource.SWIGGY, "SW-1"));
        assertTrue(registry.tryAcquire(OrderSource.SWIGGY, "SW-1"));
    }

    @Test
    void theSameReferenceFromAnotherSourceIsADifferentKey() {
        assertTrue(registry.tryAcquire(OrderSource.SWIGGY, "1001"));
        assertTrue(registry.tryAcquire(OrderSource.ZOMATO, "1001"));
        assertTrue(registry.tryAcquire(OrderSource.IN_HOUSE, "1001"));
        assertEquals(3, registry.size());
    }

    @Test
    void referencesAreCaseSensitiveLikeTheDatabase() {
        assertTrue(registry.tryAcquire(OrderSource.SWIGGY, "abc"));
        assertTrue(registry.tryAcquire(OrderSource.SWIGGY, "ABC"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertTrue(registry.tryAcquire(OrderSource.SWIGGY, "SW-1"));
        assertFalse(registry.tryAcquire(OrderSource.SWIGGY, "  SW-1 "));
        registry.release(OrderSource.SWIGGY, "SW-1  ");
        assertEquals(0, registry.size());
    }

    @Test
    void releasingAKeyThatIsNotHeldDoesNothing() {
        registry.release(OrderSource.ZOMATO, "never-acquired");
        assertEquals(0, registry.size());
        assertTrue(registry.tryAcquire(OrderSource.ZOMATO, "x"));
        registry.release(OrderSource.ZOMATO, "x");
        registry.release(OrderSource.ZOMATO, "x");
        assertEquals(0, registry.size());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> registry.tryAcquire(null, "x"));
        assertThrows(NullPointerException.class, () -> registry.tryAcquire(OrderSource.SWIGGY, null));
        assertThrows(NullPointerException.class, () -> registry.release(null, "x"));
    }

    @RepeatedTest(20)
    void exactlyOneOfManyConcurrentCallersWins() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "inflight-test"));
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    if (registry.tryAcquire(OrderSource.ZOMATO, "RACE-1")) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, winners.get());
            assertEquals(1, registry.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(20)
    void distinctKeysNeverBlockEachOtherUnderConcurrency() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "inflight-test"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ref = "REF-" + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    if (registry.tryAcquire(OrderSource.SWIGGY, ref)) {
                        winners.incrementAndGet();
                        registry.release(OrderSource.SWIGGY, ref);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertEquals(threads, winners.get());
            assertEquals(0, registry.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
