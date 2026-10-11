package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/**
 * SDD 7.13 (shared repair): a repair attempt acts on the worker it observed. If another repair has already replaced
 * that worker, the stale attempt must neither restart the healthy replacement nor see a half-installed replacement.
 * Both races are driven to the exact interleaving with latches; no sleep decides the outcome.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairRaceTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static long restartedEvents(ServiceHarness h) {
        return h.events.count(EventType.WORKER_RESTARTED);
    }

    /**
     * Repair B observes the dead generation-1 worker and is paused right after it logged {@code WORKER_DIED}, before it
     * asks for the restart. Repair A completes the restart (generation 2). B then continues: its observation is stale,
     * so it must not restart the healthy generation-2 worker.
     */
    @RepeatedTest(20)
    void aRepairThatObservedTheOldWorkerCannotRestartTheHealthyReplacement() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((s, owner, id, st) -> {
            if (s == WorkerHook.Stage.AFTER_POLL && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath("after poll");
            }
        });
        try {
            long id = p.accepted("RR-STALE");
            StationRuntime runtime = p.orch.runtime("BIRYANI");
            ExecutionException death = assertThrows(ExecutionException.class, () -> runtime.future().get(20, TimeUnit.SECONDS));
            assertInstanceOf(SimulatedWorkerDeath.class, death.getCause());

            CountDownLatch observed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Thread> stale = new AtomicReference<>();
            h.events.onEvent(e -> {
                if (e.type() == EventType.WORKER_DIED && Thread.currentThread() == stale.get()) {
                    observed.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            Thread staleRepair = new Thread(() -> p.orch.repairStation(runtime), "test-stale-repair");
            stale.set(staleRepair);
            staleRepair.start();
            assertTrue(observed.await(20, TimeUnit.SECONDS), "the stale repair has observed the dead worker");

            p.orch.repairStation(runtime); // the competing repair completes the restart while the other is paused
            assertEquals(2, runtime.currentGeneration());
            assertEquals(1, restartedEvents(h));

            release.countDown();
            staleRepair.join(20_000);
            assertFalse(staleRepair.isAlive());
            assertEquals(2, runtime.currentGeneration(), "the stale attempt did not restart the healthy generation-2 worker");
            assertEquals(1, restartedEvents(h), "exactly one restart");
            assertEquals(1, runtime.restarts());
            assertFalse(runtime.isDead());
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            release(h);
            p.stop();
        }
    }

    private static void release(ServiceHarness h) {
        h.events.onEvent(e -> { });
    }

    /**
     * {@code restartWorker} installs the new worker and attaches its future one after the other, under the runtime's
     * lock. A repair must read the pair under the same lock; otherwise it can combine the new worker with the old,
     * finished future and take the healthy new worker for a dead one. The test holds the lock in the half-installed
     * state, starts a repair, proves that it is blocked, and completes the installation.
     */
    @RepeatedTest(20)
    void aRepairNeverSeesAHalfInstalledReplacement() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((s, owner, id, st) -> {
            if (s == WorkerHook.Stage.AFTER_POLL && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath("after poll");
            }
        });
        try {
            p.accepted("RR-TORN");
            StationRuntime runtime = p.orch.runtime("BIRYANI");
            assertThrows(ExecutionException.class, () -> runtime.future().get(20, TimeUnit.SECONDS));
            StationWorker old = runtime.worker();
            old.retire();
            // a generation-2 worker that is installed but not yet running, with the finished generation-1 future still attached
            StationWorker fresh = new StationWorker(runtime, 2, h.service, p.settings, h.clock, h.events, h.metrics,
                    new AtomicReference<>(WorkerHook.NO_OP));
            Thread repair = new Thread(() -> p.orch.repairStation(runtime), "test-torn-repair");
            synchronized (runtime.lock()) {
                runtime.install(fresh);
                repair.start();
                assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), () -> repair.getState() == Thread.State.BLOCKED),
                        "the repair is waiting for the lock held by the restart in progress");
                assertEquals(0, restartedEvents(h));
                runtime.attach(new CompletableFuture<Void>()); // the new worker's (running) future
            }
            repair.join(20_000);
            assertFalse(repair.isAlive());
            assertEquals(2, runtime.currentGeneration(), "the healthy replacement was not restarted");
            assertEquals(0, restartedEvents(h));
            assertEquals(0, runtime.restarts());
        } finally {
            p.stop();
        }
    }
}
