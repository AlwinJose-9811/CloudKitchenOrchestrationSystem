package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * D-093, SDD 7.11.7, SRS FR-CON-11: an administrator can restart a {@code DEAD} worker; the restart resets the budget; the
 * automatic path still never restarts a dead station; the restart is a compare-and-set on the displayed generation and is
 * safe against a concurrent restart, a concurrent shutdown and an exhausted thread pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorRestartTest {

    private static final String CODE = "BIRYANI";

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static StationView view(PipelineHarness p) {
        return p.orch.stationViews().stream().filter(v -> v.stationCode().equals(CODE)).findFirst().orElseThrow();
    }

    private static void awaitDeath(PipelineHarness p) {
        assertThrows(ExecutionException.class, () -> p.orch.runtime(CODE).future().get(20, TimeUnit.SECONDS));
    }

    private long countEvents(EventType type) {
        return h.events.all().stream().filter(e -> e.type() == type).count();
    }

    /** Every BIRYANI generation dies on its first order until the budget (2 restarts) is spent: the station is DEAD, its order waits. */
    private long driveToDead(PipelineHarness p) throws Exception {
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && owner.startsWith(CODE + "#")) {
                throw new SimulatedWorkerDeath("every generation dies");
            }
        });
        long id = p.accepted("OR-" + System.nanoTime());
        for (int generation = 1; generation <= 2; generation++) {
            awaitDeath(p);
            p.orch.monitor().runOnce();
        }
        awaitDeath(p);
        p.orch.monitor().runOnce();
        assertTrue(p.orch.runtime(CODE).isDead());
        assertEquals(WorkerState.DEAD, view(p).worker().state());
        return id;
    }

    /**
     * Time passes while the station is dead: the order that the last dying worker had polled but not yet leased is in no queue,
     * and the reconcile of the next monitor pass (SDD 7.11.14) puts it back once it is older than the dispatch window.
     */
    private void letTheOrderAge(PipelineHarness p) {
        p.advance(Duration.ofSeconds(31), CODE);
    }

    @Test
    void anOperatorBringsADeadStationBackAndTheWaitingOrderIsDelivered() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        try {
            long id = driveToDead(p);
            int generation = p.orch.runtime(CODE).currentGeneration();
            assertEquals(3, generation);
            assertEquals("ROUTED", p.status(id), "the order waits for the operator");
            p.orch.monitor().runOnce();
            assertEquals(generation, p.orch.runtime(CODE).currentGeneration(), "the monitor never revives a dead station");

            p.orch.setWorkerHook(WorkerHook.NO_OP); // the cause is gone
            letTheOrderAge(p);
            long restartedBefore = countEvents(EventType.WORKER_RESTARTED);
            assertTrue(p.orch.operatorRestart(CODE, generation, "operator"));
            assertFalse(p.orch.runtime(CODE).isDead());
            assertEquals(generation + 1, p.orch.runtime(CODE).currentGeneration());
            assertNotEquals(WorkerState.DEAD, view(p).worker().state());
            assertEquals(1, countEvents(EventType.WORKER_GAVE_UP));
            assertEquals(restartedBefore + 1, countEvents(EventType.WORKER_RESTARTED), "the operator restart is logged as a restart");

            p.orch.monitor().runOnce(); // the next pass re-queues the stranded order
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
            assertEquals(0, h.metricsDelta().failed());
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), () -> view(p).worker().state() == WorkerState.IDLE));
            p.orch.monitor().runOnce();
            assertEquals(generation + 1, p.orch.runtime(CODE).currentGeneration(), "a healthy revived worker is left alone");
        } finally {
            p.stop();
        }
    }

    @Test
    void aStaleGenerationDoesNotReviveADeadStationAndAnUnknownStationIsRefused() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        try {
            driveToDead(p);
            int generation = p.orch.runtime(CODE).currentGeneration();
            assertFalse(p.orch.operatorRestart(CODE, generation - 1, "stale page"));
            assertFalse(p.orch.operatorRestart(CODE, generation + 1, "future page"));
            assertFalse(p.orch.operatorRestart("NOPE", 1, "unknown"));
            assertFalse(p.orch.operatorRestart(null, 1, "null"));
            assertTrue(p.orch.runtime(CODE).isDead());
            assertEquals(generation, p.orch.runtime(CODE).currentGeneration());
        } finally {
            p.stop();
        }
    }

    @Test
    void theAutomaticPathStillRefusesADeadStation() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        try {
            driveToDead(p);
            assertFalse(p.orch.restartWorker(CODE, p.orch.runtime(CODE).currentGeneration(), "automatic"), "SDD 7.11.7: no more automatic restarts");
            assertTrue(p.orch.runtime(CODE).isDead());
        } finally {
            p.stop();
        }
    }

    @Test
    void aRestartedStationHasAFreshBudgetAndGivesUpAgainOnlyWhenItIsSpentAgain() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        try {
            driveToDead(p);
            letTheOrderAge(p);
            assertTrue(p.orch.operatorRestart(CODE, p.orch.runtime(CODE).currentGeneration(), "operator")); // generation 4, the cause is still there
            p.orch.monitor().runOnce(); // re-queues the stranded order, which kills generation 4 again
            for (int restart = 1; restart <= 2; restart++) {
                awaitDeath(p);
                p.orch.monitor().runOnce();
                assertFalse(p.orch.runtime(CODE).isDead(), "restart " + restart + " of the fresh budget of 2");
            }
            assertEquals(6, p.orch.runtime(CODE).currentGeneration());
            awaitDeath(p);
            p.orch.monitor().runOnce();
            assertTrue(p.orch.runtime(CODE).isDead(), "spent again: dead again");
            assertEquals(2, countEvents(EventType.WORKER_GAVE_UP));
            p.orch.setWorkerHook(WorkerHook.NO_OP);
            assertTrue(p.orch.operatorRestart(CODE, p.orch.runtime(CODE).currentGeneration(), "operator again"), "and the operator can do it again");
        } finally {
            p.stop();
        }
    }

    @Test
    void anOperatorRestartOfAHealthyWorkerAlsoWorksAndDoesNotSpendTheBudget() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 1, 300));
        try {
            for (int i = 1; i <= 4; i++) {
                assertTrue(p.orch.operatorRestart(CODE, p.orch.runtime(CODE).currentGeneration(), "operator " + i), "restart " + i + " with an automatic budget of 1");
            }
            assertFalse(p.orch.runtime(CODE).isDead());
            assertEquals(5, p.orch.runtime(CODE).currentGeneration());
            long id = p.accepted("OR-HEALTHY");
            p.awaitDelivered(id);
        } finally {
            p.stop();
        }
    }

    @RepeatedTest(20)
    void twoOperatorsRestartingTheSameDeadStationRestartItOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-operator"));
        try {
            driveToDead(p);
            p.orch.setWorkerHook(WorkerHook.NO_OP);
            int generation = p.orch.runtime(CODE).currentGeneration();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return p.orch.operatorRestart(CODE, generation, "operator A");
            });
            Future<Boolean> second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return p.orch.operatorRestart(CODE, generation, "operator B");
            });
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0), "exactly one click won");
            assertEquals(generation + 1, p.orch.runtime(CODE).currentGeneration());
            assertFalse(p.orch.runtime(CODE).isDead());
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }

    @RepeatedTest(20)
    void anOperatorRestartRacingTheShutdownNeverThrowsAndLeavesNoWorkerThreadBehind() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-race"));
        try {
            driveToDead(p);
            p.orch.setWorkerHook(WorkerHook.NO_OP);
            int generation = p.orch.runtime(CODE).currentGeneration();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> restart = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return p.orch.operatorRestart(CODE, generation, "operator");
            });
            Future<OrderOrchestrator.StopOutcome> stop = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return p.orch.stop(Duration.ofSeconds(10), Duration.ofSeconds(10));
            });
            restart.get(30, TimeUnit.SECONDS); // true or false, but never an exception such as a rejected submission
            OrderOrchestrator.StopOutcome outcome = stop.get(30, TimeUnit.SECONDS);
            assertTrue(outcome == OrderOrchestrator.StopOutcome.DRAINED || outcome == OrderOrchestrator.StopOutcome.STOPPED, "stop ended cleanly: " + outcome);
            assertFalse(p.orch.operatorRestart(CODE, p.orch.runtime(CODE).currentGeneration(), "after the stop"), "nothing restarts once the application stops");
        } finally {
            executor.shutdownNow();
            p.stop();
        }
        p.assertNoStationThreads();
    }

    /**
     * Deterministic interleaving of the shutdown guard: the test holds the station's lock, so an operator restart that has already
     * passed its "not stopping" check waits for it, and {@code stop} (which sets "stopping" first) must wait for the same lock
     * before it drains. Whoever gets the lock after the release, the restart must be refused and must not touch the station.
     */
    @RepeatedTest(20)
    void aRestartThatIsWaitingForTheLockWhenTheShutdownStartsIsRefusedAndStopWaitsForTheLock() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        try {
            driveToDead(p);
            p.orch.setWorkerHook(WorkerHook.NO_OP);
            StationRuntime runtime = p.orch.runtime(CODE);
            int generation = runtime.currentGeneration();
            java.util.concurrent.atomic.AtomicReference<Boolean> restarted = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<OrderOrchestrator.StopOutcome> stopped = new java.util.concurrent.atomic.AtomicReference<>();
            Thread restart = new Thread(() -> restarted.set(p.orch.operatorRestart(CODE, generation, "operator")), "test-restart");
            Thread stop = new Thread(() -> stopped.set(p.orch.stop(Duration.ofSeconds(10), Duration.ofSeconds(10))), "test-stop");
            synchronized (runtime.lock()) {
                restart.start();
                assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> restart.getState() == Thread.State.BLOCKED),
                        "the restart passed its check and waits for the lock");
                stop.start();
                assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.orch.isStopping() && stop.getState() == Thread.State.BLOCKED),
                        "stop has set 'stopping' and waits for the lock before it drains: " + stop.getState());
            }
            restart.join(30_000);
            stop.join(30_000);
            assertFalse(restart.isAlive() || stop.isAlive());
            assertEquals(Boolean.FALSE, restarted.get(), "refused: the application is stopping");
            assertEquals(generation, runtime.currentGeneration(), "the refused restart changed nothing");
            assertTrue(runtime.isDead());
            OrderOrchestrator.StopOutcome outcome = stopped.get();
            assertTrue(outcome == OrderOrchestrator.StopOutcome.DRAINED || outcome == OrderOrchestrator.StopOutcome.STOPPED, "stop ended cleanly: " + outcome);
        } finally {
            p.stop();
        }
        p.assertNoStationThreads();
    }

    @Test
    void whenNoThreadCanBeStartedTheStationStaysDeadInsteadOfThrowingAndALaterRestartSucceeds() throws Exception {
        // pool = 3 stations + 1 spare; two retired BIRYANI generations that cannot end occupy the spare and the station's own thread
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 1, 300));
        CountDownLatch stuck = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && owner.startsWith(CODE + "#")) {
                stuck.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true; // a thread that ignores the interrupt: the worst case for the pool
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            p.accepted("OR-POOL-1");
            p.accepted("OR-POOL-2");
            p.accepted("OR-POOL-3");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), () -> stuck.getCount() == 1), "generation 1 is stuck in its hook");
            assertTrue(p.orch.operatorRestart(CODE, 1, "operator"), "generation 2 gets the spare thread");
            assertTrue(stuck.await(20, TimeUnit.SECONDS), "generation 2 is stuck too");

            boolean started = p.orch.operatorRestart(CODE, 2, "operator");
            assertFalse(started, "no thread is left: refused, not thrown");
            assertTrue(p.orch.runtime(CODE).isDead());
            assertEquals(WorkerState.DEAD, view(p).worker().state());
            assertEquals(1, countEvents(EventType.WORKER_GAVE_UP));

            p.orch.setWorkerHook(WorkerHook.NO_OP);
            release.countDown(); // the two stuck generations end
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30),
                    () -> p.orch.operatorRestart(CODE, p.orch.runtime(CODE).currentGeneration(), "operator retry")), "once threads are free the operator restart works");
            assertFalse(p.orch.runtime(CODE).isDead());
            long id = p.accepted("OR-POOL-4");
            p.awaitDelivered(id);
        } finally {
            release.countDown();
            p.stop();
        }
    }
}
