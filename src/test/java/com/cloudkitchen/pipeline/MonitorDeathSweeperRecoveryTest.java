package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RW-14, RW-15, RI-1, RI-4: with the monitor never run, one sweeper pass repairs a killed worker and recovers the lost id. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonitorDeathSweeperRecoveryTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private OrphanSweeper sweeperOf(PipelineHarness p) {
        return new OrphanSweeper(h.service, p.orch, new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE),
                OrphanSweeperTest.SETTINGS, h.clock, h.events);
    }

    @ParameterizedTest(name = "death at {0}/{1}")
    @CsvSource({"AFTER_POLL,", "AFTER_LEASE,", "BEFORE_STEP,PREPARING"})
    void oneSweeperPassRestartsTheKilledWorkerOnceAndTheOrderIsDeliveredWithoutReachingTheOrphanThreshold(
            WorkerHook.Stage stage, OrderStatus step) throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((s, owner, id, st) -> {
            if (s == stage && (step == null || st == step) && owner.equals("BIRYANI#1") && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath(s + "/" + st);
            }
        });
        try {
            long id = p.accepted("MD-" + stage + "-" + step);
            ExecutionException death = assertThrows(ExecutionException.class, () -> p.orch.runtime("BIRYANI").future().get(20, TimeUnit.SECONDS));
            assertInstanceOf(SimulatedWorkerDeath.class, death.getCause());
            p.advance(Duration.ofSeconds(61), "BIRYANI"); // 61 s < recovery.orphan.after.seconds (120 s): only the repair step can help

            sweeperOf(p).runOnce();               // the monitor is never run in this test

            p.awaitDelivered(id);
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration());
            assertEquals(1, h.events.all().stream().filter(e -> e.type() == EventType.WORKER_RESTARTED).count());
            assertEquals(0, h.events.countFor(EventType.ORDER_REENQUEUED, id), "the orphan path was not needed or used");
            p.assertEachEdgeOnce(id);
            assertEquals(1, h.metricsDelta().processed());
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @RepeatedTest(20)
    void monitorAndSweeperRepairingTheSameDeadWorkerAtOnceRestartItExactlyOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((s, owner, id, st) -> {
            if (s == WorkerHook.Stage.AFTER_POLL && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath("after poll");
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-repair-race"));
        try {
            long id = p.accepted("MD-RACE");
            assertThrows(ExecutionException.class, () -> p.orch.runtime("BIRYANI").future().get(20, TimeUnit.SECONDS));
            OrphanSweeper sweeper = sweeperOf(p);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> monitor = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                p.orch.monitor().runOnce();
                return null;
            });
            Future<?> sweep = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                sweeper.runOnce();
                return null;
            });
            monitor.get(30, TimeUnit.SECONDS);
            sweep.get(30, TimeUnit.SECONDS);
            p.awaitDelivered(id);
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration(), "one new generation, not two");
            assertEquals(1, h.events.all().stream().filter(e -> e.type() == EventType.WORKER_RESTARTED).count());
            p.assertEachEdgeOnce(id);
            assertTrue(h.metricsDelta().processed() == 1);
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }
}
