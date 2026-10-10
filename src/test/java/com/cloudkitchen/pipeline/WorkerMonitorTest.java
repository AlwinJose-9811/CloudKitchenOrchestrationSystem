package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.11.4-7.11.7: detection of dead and stalled workers, restart, the restart budget and the compare-and-set. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerMonitorTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static StationView view(PipelineHarness p, String code) {
        return p.orch.stationViews().stream().filter(v -> v.stationCode().equals(code)).findFirst().orElseThrow();
    }

    private static void awaitDeath(PipelineHarness p) {
        assertThrows(ExecutionException.class, () -> p.orch.runtime("BIRYANI").future().get(20, TimeUnit.SECONDS));
    }

    private long countEvents(EventType type) {
        return h.events.all().stream().filter(e -> e.type() == type).count();
    }

    @Test
    void healthyWorkersAreLeftAlone() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            p.orch.monitor().runOnce();
            p.orch.monitor().runOnce();
            assertTrue(p.orch.stationViews().stream().allMatch(v -> v.worker().generation() == 1 && v.worker().restarts() == 0));
            assertEquals(0, countEvents(EventType.WORKER_RESTARTED));
            assertNotNull(p.orch.monitor().lastPass(), "the dashboard shows when the last pass ended");
        } finally {
            p.stop();
        }
    }

    @Test
    void aDeadWorkerIsReplacedByTheNextGenerationOnTheSameQueueUnderItsOwnThreadName() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath("test");
            }
        });
        try {
            long id = p.accepted("WM-DEAD");
            awaitDeath(p);
            StationRuntime runtime = p.orch.runtime("BIRYANI");
            var queueBefore = runtime.queue();
            p.orch.monitor().runOnce();
            p.awaitDelivered(id);
            assertEquals(2, view(p, "BIRYANI").worker().generation());
            assertEquals(1, view(p, "BIRYANI").worker().restarts());
            assertTrue(runtime.queue() == queueBefore, "the queue object is the same, so its contents were kept");
            assertEquals(1, countEvents(EventType.WORKER_DIED));
            assertEquals(1, countEvents(EventType.WORKER_RESTARTED));
            assertTrue(PipelineHarness.stationThreads().stream().anyMatch(t -> t.getName().equals("station-BIRYANI-worker-g2")
                    || t.getName().equals("station-BIRYANI-worker")), "the replacement runs under a station thread name");
            assertEquals("worker:BIRYANI#2", p.actors(id).get(p.actors(id).size() - 1));
        } finally {
            p.stop();
        }
    }

    @Test
    void aStalledWorkerIsRetiredAndReplacedWhileTheOldOneIsFencedOut() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.BEFORE_STEP && owner.equals("BIRYANI#1") && step == OrderStatus.ROUTED) {
                blocked.countDown();
                StaleWorkerWakeTest.hang(release);
            }
        });
        try {
            long id = p.accepted("WM-STALL");
            assertTrue(blocked.await(20, TimeUnit.SECONDS));
            StationWorker old = p.orch.runtime("BIRYANI").worker();
            p.advance(Duration.ofSeconds(61), "BIRYANI");
            p.orch.monitor().runOnce();
            p.awaitDelivered(id);
            assertTrue(old.isRetired());
            assertEquals(1, countEvents(EventType.WORKER_STALLED));
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration());
            release.countDown();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), old::hasExited));
            p.assertEachEdgeOnce(id);
            assertEquals(1, h.metricsDelta().processed());
        } finally {
            release.countDown();
            p.stop();
        }
    }

    @Test
    void afterTheRestartBudgetTheStationIsDeadAndItsOrdersStayInTheDatabase() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withHealth(60_000, 2, 300));
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && owner.startsWith("BIRYANI#")) {
                throw new SimulatedWorkerDeath("every generation dies");
            }
        });
        try {
            long id = p.accepted("WM-BUDGET");
            for (int generation = 1; generation <= 2; generation++) {
                awaitDeath(p);
                p.orch.monitor().runOnce(); // restart (budget 2)
                assertEquals(generation + 1, p.orch.runtime("BIRYANI").currentGeneration());
            }
            awaitDeath(p);
            p.orch.monitor().runOnce(); // the third death exceeds the budget
            assertTrue(p.orch.runtime("BIRYANI").isDead());
            assertEquals(WorkerState.DEAD, view(p, "BIRYANI").worker().state());
            assertEquals(1, countEvents(EventType.WORKER_GAVE_UP));
            assertFalse(p.orch.restartWorker("BIRYANI", p.orch.runtime("BIRYANI").currentGeneration(), "again"), "no more automatic restarts");
            p.orch.monitor().runOnce();
            assertEquals(3, p.orch.runtime("BIRYANI").currentGeneration());
            assertEquals("ROUTED", p.status(id), "the order was not lost, failed or delivered: it waits for an operator");
            assertEquals(0, h.metricsDelta().failed());
        } finally {
            p.stop();
        }
    }

    @Test
    void twoRepairsOfTheSameDeadWorkerRestartItOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath("test");
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-repair"));
        try {
            p.accepted("WM-CAS");
            awaitDeath(p);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return p.orch.restartWorker("BIRYANI", 1, "monitor");
            });
            Future<Boolean> second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return p.orch.restartWorker("BIRYANI", 1, "sweeper");
            });
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0), "exactly one repair won");
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration());
            assertEquals(1, view(p, "BIRYANI").worker().restarts());
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }

    @Test
    void aFailedLeaseReclaimDuringRestartLosesNothingBecauseTheLeaseExpiresAndReconcileRecovers() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_LEASE && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath("after lease");
            }
        });
        try {
            long id = p.accepted("WM-RW7");
            awaitDeath(p);
            h.orderFaults.failBefore("expireLeasesOf", 1, new SQLTransientConnectionException("outage", "08S01", 0));
            p.orch.monitor().runOnce(); // the worker is replaced but its lease could not be cleared
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration());
            assertEquals("ROUTED", p.status(id));
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id));
            assertEquals("BIRYANI#1", h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "the dead owner's lease is still valid");

            p.advance(Duration.ofSeconds(31)); // the lease expires by itself
            p.orch.monitor().runOnce();        // the next pass reconciles from the database
            p.awaitDelivered(id);
            assertEquals(2, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id));
            p.assertEachEdgeOnce(id);
        } finally {
            p.stop();
        }
    }

    @Test
    void theScheduledMonitorRunsUnderItsOwnNameAndStopsWithTheOrchestrator() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            p.orch.monitor().start();
            assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("worker-monitor") && t.isAlive()));
            Thread monitorThread = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("worker-monitor")).findFirst().orElseThrow();
            p.stop();
            monitorThread.join(5000);
            assertFalse(monitorThread.isAlive(), "stopping the orchestrator stops the monitor");
        } finally {
            p.stop();
        }
    }
}
