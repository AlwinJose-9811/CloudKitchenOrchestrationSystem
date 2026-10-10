package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-RES-04, NFR-REL-03 with the whole application context: ordered, bounded, idempotent shutdown; unfinished orders
 * stay recoverable; no thread owned by the application survives; a second start finishes what the first left.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GracefulShutdownTest {

    private static final Set<String> OWNED = Set.of("worker-monitor", "reconcile-scheduler", "feed-simulator", "recovery-loader", "ck-shutdown-hook");

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static List<Thread> ownedThreads() {
        List<Thread> found = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && (t.getName().startsWith("station-") || OWNED.contains(t.getName()))) {
                found.add(t);
            }
        }
        return found;
    }

    private static void awaitOwnedThreadsGone() throws InterruptedException {
        for (Thread t : ownedThreads()) {
            t.join(10_000);
        }
        assertEquals(List.of(), ownedThreads(), "no thread owned by the application is left");
    }

    private long remaining() throws Exception {
        return h.t.number("SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED','FAILED')");
    }

    @Test
    void aRunningApplicationShutsDownInOrderWithinTheTimeoutAndLeavesNoThreadOrPoolBehind() throws Exception {
        h.reset();
        DbFixture db = h.db;
        AppContext app = AppFixture.start(db, false);
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        assertTrue(app.simulator().start(6000));
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> app.simulator().produced() >= 20));
        long begin = System.nanoTime();
        app.close();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        ShutdownCoordinator.Outcome outcome = app.shutdownCoordinator().outcome();
        assertNotNull(outcome);
        assertTrue(outcome.complete() && !outcome.anyFailed(), outcome.toString());
        assertEquals(List.of("stop recovery", "stop accepting orders", "stop the feed simulator", "stop the sweeper and the worker monitor",
                "drain and stop the workers", "close the connection pool", "close the event log"),
                outcome.steps().stream().map(ShutdownCoordinator.StepResult::name).toList(), "the documented order (SDD 7.6)");
        assertTrue(millis < (5 + 5 + 5) * 1000L, "bounded by drain + await + 5 s, took " + millis + " ms");
        assertFalse(app.orders().isAccepting());
        SubmissionResult late = app.orders().submitOrder(h.t.request(OrderSource.SWIGGY, "GS-LATE", "BIRYANI", "Raita", 1));
        assertEquals(SubmissionResult.SHUTTING_DOWN, late.reasonCode(), "new submissions are refused");
        assertFalse(app.simulator().isRunning());
        long producedAtStop = app.simulator().produced();
        awaitOwnedThreadsGone();
        assertEquals(producedAtStop, app.simulator().produced(), "the simulator produced nothing after the shutdown");
        assertThrows(PersistenceException.class, () -> app.database().withConnection(c -> 1), "the pool is closed");
        // what was unfinished is in the database and the next start finishes it
        AppContext next = AppFixture.start(db, false);
        try {
            assertTrue(next.awaitReady(60, TimeUnit.SECONDS));
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
                try {
                    return remaining() == 0;
                } catch (Exception e) {
                    return false;
                }
            }), "the second start completed every unfinished order");
            assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'FAILED'"));
        } finally {
            next.close();
        }
        awaitOwnedThreadsGone();
    }

    @Test
    void aShutdownTriggeredByTheListenerAndByTheJvmHookAtTheSameTimeRunsOnce() throws Exception {
        h.reset();
        AppContext app = AppFixture.start(h.db, true);
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        assertTrue(app.shutdownCoordinator().jvmHookInstalled());
        AtomicInteger finalSteps = new AtomicInteger();
        app.shutdownCoordinator().register("test counter", () -> {
            finalSteps.incrementAndGet();
            return null;
        });
        ExecutorService callers = Executors.newFixedThreadPool(4, r -> new Thread(r, "test-listener-caller"));
        Thread hook = new Thread(() -> app.shutdownCoordinator().shutdown(), "ck-shutdown-hook");
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(callers.submit(app::close));
            }
            hook.start();
            futures.add(callers.submit(app::close));
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            hook.join(60_000);
            assertEquals(1, finalSteps.get(), "the steps ran once");
            assertTrue(app.shutdownCoordinator().isDone());
            assertFalse(app.shutdownCoordinator().jvmHookInstalled());
            assertTrue(app.shutdownCoordinator().outcome().steps().stream().noneMatch(ShutdownCoordinator.StepResult::failed));
        } finally {
            callers.shutdownNow();
        }
        awaitOwnedThreadsGone();
    }

    @Test
    void stuckWorkersAreInterruptedAfterTheTimeoutsAndTheirOrdersStayRecoverable() throws Exception {
        h.reset();
        AppContext app = AppFixture.start(h.db, false, "worker.delivery.ms", "60000", "shutdown.drain.seconds", "1", "shutdown.await.seconds", "1");
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        SubmissionResult r = app.orders().submitOrder(h.t.request(OrderSource.ZOMATO, "GS-STUCK", "BIRYANI", "Chicken Biryani", 1));
        assertEquals(SubmissionResult.Outcome.ACCEPTED, r.outcome());
        long id = r.orderId();
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> {
            try {
                return "OUT_FOR_DELIVERY".equals(h.t.text("SELECT status FROM orders WHERE id = ?", id));
            } catch (Exception e) {
                return false;
            }
        }), "the worker is inside its 60 s delivery step");
        long begin = System.nanoTime();
        app.close();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue(millis < (1 + 1 + 5) * 1000L, "NFR-REL-03: drain + await + 5 s even with a stuck worker, took " + millis + " ms");
        ShutdownCoordinator.StepResult drain = app.shutdownCoordinator().outcome().steps().stream()
                .filter(s -> s.name().equals("drain and stop the workers")).findFirst().orElseThrow();
        assertFalse(drain.failed());
        assertTrue(drain.note().contains("were interrupted"), "the forced path is recorded: " + drain.note());
        assertEquals("OUT_FOR_DELIVERY", h.t.text("SELECT status FROM orders WHERE id = ?", id), "unfinished, not completed and not failed");
        awaitOwnedThreadsGone();

        AppContext next = AppFixture.start(h.db, false);
        try {
            assertTrue(next.awaitReady(60, TimeUnit.SECONDS));
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> {
                try {
                    return "DELIVERED".equals(h.t.text("SELECT status FROM orders WHERE id = ?", id));
                } catch (Exception e) {
                    return false;
                }
            }), "the next start resumed the order from OUT_FOR_DELIVERY");
            assertEquals(1, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'DELIVERED'", id));
        } finally {
            next.close();
        }
        awaitOwnedThreadsGone();
    }

    @Test
    void aCallerThatIsInterruptedWhileShuttingDownStillGetsACompleteShutdownAndKeepsItsFlag() throws Exception {
        h.reset();
        AppContext app = AppFixture.start(h.db, false);
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            app.close();
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is set again when the shutdown returns");
        } finally {
            Thread.interrupted();
        }
        assertTrue(app.shutdownCoordinator().outcome().complete());
        assertTrue(app.shutdownCoordinator().outcome().steps().stream().noneMatch(ShutdownCoordinator.StepResult::failed),
                "the pool and the workers were still closed: " + app.shutdownCoordinator().outcome());
        awaitOwnedThreadsGone();
    }

    @Test
    void aShutdownDuringARecoveryThatIsWaitingForQueueRoomEndsTheRecoveryAndNeverOpensTheApplication() throws Exception {
        h.reset();
        for (int i = 0; i < 10; i++) {
            h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "GS-REC-" + i, "BIRYANI", "Chicken Biryani", 1)); // ROUTED, nobody queued
        }
        // two slots and a 2 s preparation: recovery has to wait for the worker for a long time
        AppContext app = AppFixture.start(h.db, false, "station.queue.capacity", "2", "worker.prep.ms", "2000");
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> app.recovery().isRecovering()
                && app.orchestrator().runtime("BIRYANI").size() == 2), "recovery filled the queue and is waiting for room");
        long begin = System.nanoTime();
        app.close();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue(millis < (5 + 5 + 5) * 1000L, "took " + millis + " ms");
        assertFalse(app.recovery().isRecovering(), "the recovery thread ended");
        assertFalse(app.orders().isAccepting(), "an application whose recovery was cut short never starts accepting");
        assertFalse(app.awaitReady(0, TimeUnit.MILLISECONDS));
        assertTrue(app.shutdownCoordinator().outcome().steps().stream().noneMatch(ShutdownCoordinator.StepResult::failed));
        assertTrue(remaining() > 0, "the orders recovery had not handed back are still non-terminal in the database");
        awaitOwnedThreadsGone();

        AppContext next = AppFixture.start(h.db, false);
        try {
            assertTrue(next.awaitReady(60, TimeUnit.SECONDS));
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
                try {
                    return remaining() == 0;
                } catch (Exception e) {
                    return false;
                }
            }), "the next start completed all ten orders");
            assertEquals(10, h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"));
        } finally {
            next.close();
        }
        awaitOwnedThreadsGone();
    }
}
