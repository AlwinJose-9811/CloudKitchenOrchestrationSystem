package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.5, FR-RES-01: start/stop/rate, generated orders, bounded non-overlapping ticks, survival of expected failures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedSimulatorTest {

    private static final Pattern REF = Pattern.compile("SIM-(SWIGGY|ZOMATO|IN_HOUSE)-\\d+-[0-9a-f]{8}");

    private ServiceHarness h;
    private MenuService menu;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
        menu = new MenuService(h.db.database(), h.t.menu, h.t.stock, h.t.stations);
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @BeforeEach
    void clean() throws Exception {
        h.reset();
    }

    private FeedSimulator simulator(FeedSimulator.OrderGenerator generator, int rate, int max) {
        return new FeedSimulator(h.service, generator, rate, max, h.clock, h.events);
    }

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals(name) && t.isAlive());
    }

    @Test
    void startStopAndRateFollowTheirContract() throws Exception {
        FeedSimulator sim = simulator(seq -> Optional.empty(), 30, 600);
        try {
            assertFalse(sim.isRunning());
            assertEquals(30, sim.getRate());
            assertThrows(IllegalArgumentException.class, () -> sim.start(0));
            assertThrows(IllegalArgumentException.class, () -> sim.start(601));
            assertThrows(IllegalArgumentException.class, () -> sim.setRate(601));
            assertTrue(sim.start(120));
            assertFalse(sim.start(60), "already running");
            assertTrue(sim.isRunning());
            assertEquals(120, sim.getRate());
            sim.setRate(240);
            assertEquals(240, sim.getRate());
            assertTrue(sim.isRunning(), "a rate change does not stop it");
            assertTrue(sim.stop());
            assertFalse(sim.stop(), "already stopped");
            assertFalse(sim.isRunning());
            assertEquals(1, h.events.countFor(com.cloudkitchen.io.EventType.SIMULATOR_STARTED, 0L));
            assertEquals(1, h.events.countFor(com.cloudkitchen.io.EventType.SIMULATOR_STOPPED, 0L));
        } finally {
            sim.shutdown();
        }
        assertFalse(threadAlive("feed-simulator"), "the scheduler thread ended");
    }

    @Test
    void theGeneratedOrdersCoverAllSourcesWithUniqueValidReferences() throws Exception {
        FeedSimulator.OrderGenerator generator = FeedSimulator.randomGenerator(h.routing, menu, new Random(42));
        Set<String> refs = new HashSet<>();
        Set<OrderSource> sources = new HashSet<>();
        Set<String> brands = new HashSet<>();
        for (long seq = 1; seq <= 90; seq++) {
            OrderRequest r = generator.next(seq).orElseThrow();
            assertTrue(REF.matcher(r.externalRef()).matches(), r.externalRef());
            assertTrue(r.externalRef().startsWith("SIM-" + r.source().name() + "-" + seq + "-"));
            assertTrue(refs.add(r.externalRef()), "duplicate reference " + r.externalRef());
            assertTrue(r.paymentMode().allowedFor(r.source()));
            assertTrue(r.items().size() >= 1 && r.items().size() <= 3 && r.items().stream().allMatch(i -> i.quantity() >= 1 && i.quantity() <= 3));
            assertEquals(r.items().size(), r.items().stream().map(i -> i.itemId()).distinct().count(), "one line per item");
            assertTrue(r.deliveryDistanceKm().compareTo(new java.math.BigDecimal("0.50")) >= 0
                    && r.deliveryDistanceKm().compareTo(new java.math.BigDecimal("12.00")) <= 0);
            sources.add(r.source());
            brands.add(r.brandCode());
        }
        assertEquals(Set.of(OrderSource.values()), sources);
        assertEquals(Set.of("BIRYANI", "BURGER", "PIZZA"), brands);
        assertTrue(Set.of(PaymentMode.values()).containsAll(Set.of(PaymentMode.PREPAID_AGGREGATOR)));
    }

    @Test
    void everyOrderGoesThroughTheServiceSoItIsValidatedStoredAndItsStockDeducted() throws Exception {
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        FeedSimulator sim = simulator(FeedSimulator.randomGenerator(h.routing, menu, new Random(7)), 60, 600);
        try {
            for (int i = 0; i < 40; i++) {
                sim.produceOne();
            }
            assertEquals(40, sim.produced());
            FeedSimulator.Stats stats = sim.stats();
            assertEquals(40, stats.accepted() + stats.notAccepted());
            assertEquals(stats.accepted(), h.t.number("SELECT COUNT(*) FROM orders"));
            assertEquals(3, h.t.number("SELECT COUNT(DISTINCT source) FROM orders"));
            assertEquals(stats.accepted(), h.t.number("SELECT COUNT(DISTINCT external_ref) FROM orders"), "every reference is unique");
            PipelineHarness.assertStockLedger(h, initial);
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void limitedStockProducesExpectedRejectionsAndNeverAnError() throws Exception {
        h.t.execute("UPDATE inventory SET stock_qty = 3");
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        FeedSimulator sim = simulator(FeedSimulator.randomGenerator(h.routing, menu, new Random(3)), 60, 600);
        try {
            for (int i = 0; i < 60; i++) {
                sim.produceOne();
            }
            FeedSimulator.Stats stats = sim.stats();
            assertTrue(stats.accepted() > 0 && stats.notAccepted() > 0, "some orders fit, some meet an empty shelf: " + stats);
            assertEquals(0, stats.errors());
            PipelineHarness.assertStockLedger(h, initial);
            assertEquals(h.metricsDelta().rejected(), stats.notAccepted());
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void aFailingOrEmptyGeneratorNeverEndsTheSchedule() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(6);
        FeedSimulator sim = simulator(seq -> {
            int n = calls.incrementAndGet();
            enough.countDown();
            if (n == 2) {
                throw new IllegalStateException("generator bug");
            }
            if (n == 3) {
                throw new com.cloudkitchen.exception.PersistenceException("db down", new java.sql.SQLException("x"));
            }
            return Optional.empty();
        }, 6000, 6000);
        try {
            assertTrue(sim.start(6000));
            assertTrue(enough.await(20, TimeUnit.SECONDS), "the schedule kept running after two failures");
            sim.stop();
            assertEquals(2, sim.stats().errors());
            assertEquals(0, sim.produced(), "nothing was produced from empty or failed ticks");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void ticksNeverOverlapAndTheRateIsBoundedByTheFixedDelay() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch ticks = new CountDownLatch(40);
        FeedSimulator sim = simulator(seq -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            ticks.countDown();
            inside.decrementAndGet();
            return Optional.empty();
        }, 6000, 6000);
        try {
            sim.start(6000);
            assertTrue(ticks.await(20, TimeUnit.SECONDS));
            sim.stop();
            assertEquals(1, maxInside.get());
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void stopWaitsForATickThatIsAlreadyRunningAndNothingIsProducedAfterwards() throws Exception {
        CountDownLatch insideTick = new CountDownLatch(1);
        CountDownLatch releaseTick = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FeedSimulator sim = simulator(seq -> {
            calls.incrementAndGet();
            insideTick.countDown();
            try {
                releaseTick.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }, 6000, 6000);
        Thread stopper = new Thread(sim::stop, "test-stopper");
        try {
            sim.start(6000);
            assertTrue(insideTick.await(10, TimeUnit.SECONDS));
            stopper.start();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> stopper.getState() == Thread.State.TIMED_WAITING),
                    "stop() is waiting (bounded) for the running tick");
            releaseTick.countDown();
            stopper.join(10_000);
            assertFalse(stopper.isAlive());
            int callsAtStop = calls.get();
            sim.shutdown();
            assertEquals(callsAtStop, calls.get(), "no tick started after stop() returned");
        } finally {
            releaseTick.countDown();
            sim.shutdown();
        }
    }

    /** Waits on the latch and ignores interrupts, like a call stuck in a blocking driver; returns when released. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                if (latch.await(30, TimeUnit.SECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @RepeatedTest(20)
    void aTickThatIgnoresInterruptsCannotHoldStopOrShutdownBeyondTheBound() throws Exception {
        CountDownLatch insideTick = new CountDownLatch(1);
        CountDownLatch releaseTick = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FeedSimulator sim = new FeedSimulator(h.service, seq -> {
            calls.incrementAndGet();
            insideTick.countDown();
            awaitIgnoringInterrupts(releaseTick);
            return Optional.empty();
        }, 6000, 6000, h.clock, h.events, Duration.ofMillis(300));
        try {
            assertTrue(sim.start(6000));
            assertTrue(insideTick.await(10, TimeUnit.SECONDS));
            long begin = System.nanoTime();
            assertTrue(sim.stop(), "it was running");
            long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(stopMillis < 5_000, "stop() returned after its 300 ms bound, not after the tick: " + stopMillis + " ms");
            assertFalse(sim.isRunning());
            assertTrue(sim.isTickRunning(), "the tick is still blocked and is reported as such");

            begin = System.nanoTime();
            assertFalse(sim.shutdown(), "the thread is still inside the tick that ignored the interrupt");
            long shutdownMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(shutdownMillis < 5_000, "shutdown() is bounded too: " + shutdownMillis + " ms");
            assertTrue(threadAlive("feed-simulator"));

            releaseTick.countDown();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> !threadAlive("feed-simulator")),
                    "the thread ends as soon as the blocked call returns");
            assertEquals(1, calls.get(), "no tick started after stop()");
            assertFalse(sim.isTickRunning());
        } finally {
            releaseTick.countDown();
            sim.shutdown();
        }
    }

    @RepeatedTest(20)
    void aTickBlockedInsideASubmissionIsInterruptedByShutdownAndTheThreadEnds() throws Exception {
        CountDownLatch insideSubmit = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger interrupts = new AtomicInteger();
        h.hook.set(stage -> {
            if (stage == com.cloudkitchen.service.SubmissionHook.Stage.AFTER_TX1) {
                insideSubmit.countDown();
                try {
                    never.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            }
        });
        FeedSimulator sim = new FeedSimulator(h.service, FeedSimulator.randomGenerator(h.routing, menu, new Random(5)), 6000, 6000,
                h.clock, h.events, Duration.ofSeconds(2));
        try {
            assertTrue(sim.start(6000));
            assertTrue(insideSubmit.await(10, TimeUnit.SECONDS), "the tick is inside submitOrder, after its transaction committed");
            assertTrue(sim.shutdown(), "stop() timed out, shutdownNow interrupted the submission and the thread ended in time");
            assertEquals(1, interrupts.get(), "the blocked submission was interrupted exactly once");
            assertFalse(threadAlive("feed-simulator"));
            assertEquals(1, sim.produced(), "only the one submission that was already running");
            assertEquals(1, h.t.number("SELECT COUNT(*) FROM orders"), "its order was committed by TX1 and is recoverable");
        } finally {
            never.countDown();
            sim.shutdown();
        }
    }

    @Test
    void theInjectedClockStampsTheLastTick() {
        FeedSimulator sim = simulator(seq -> Optional.empty(), 30, 600);
        try {
            assertEquals(null, sim.stats().lastTickAt());
            sim.produceOne();
            assertEquals(h.clock.instant(), sim.stats().lastTickAt());
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void shutdownRestoresTheInterruptFlagOfAnInterruptedCallerAndStillEndsTheThread() throws Exception {
        FeedSimulator sim = simulator(seq -> Optional.empty(), 30, 600);
        sim.start(30);
        Thread.currentThread().interrupt();
        try {
            sim.shutdown();
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is set again after the interrupted wait");
        } finally {
            Thread.interrupted();
        }
        assertFalse(sim.isRunning());
        // the scheduler was told to stop immediately; once the pending wait is over the thread is gone
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> !threadAlive("feed-simulator")));
    }

    @Test
    void aSimulatorThatWasShutDownCannotBeStartedAgain() {
        FeedSimulator sim = simulator(seq -> Optional.empty(), 30, 600);
        sim.shutdown();
        assertFalse(sim.start(30), "no scheduler is left; the call reports it instead of throwing");
        assertFalse(sim.isRunning());
    }
}
