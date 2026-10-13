package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** RW-9, RI-5: a worker that hangs holding a lease is replaced; the order is delivered once and the zombie is harmless. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerHangRecoveryTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @RepeatedTest(20)
    void aHungWorkerIsReplacedAndWhenItWakesItFindsTheOrderFinished() throws Exception {
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
            long id = p.accepted("WHR-1");
            assertTrue(blocked.await(20, TimeUnit.SECONDS));
            StationWorker zombie = p.orch.runtime("BIRYANI").worker();
            assertEquals("BIRYANI#1", h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "the hung worker still owns a valid lease");

            p.advance(Duration.ofSeconds(61), "BIRYANI"); // the heartbeat is older than worker.stall.seconds; the lease has expired
            p.orch.monitor().runOnce();
            p.awaitDelivered(id);
            assertEquals(1, h.events.all().stream().filter(e -> e.type() == EventType.WORKER_STALLED).count());
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration());
            assertEquals(List_of("worker:BIRYANI#2"), p.actors(id).subList(p.actors(id).size() - 1, p.actors(id).size()));
            long processedBeforeWake = h.metricsDelta().processed();

            release.countDown(); // the zombie wakes: its fenced step changes 0 rows and it finds a terminal order
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), zombie::hasExited));
            assertEquals(processedBeforeWake, h.metricsDelta().processed(), "the zombie counted nothing");
            assertEquals(1, h.metricsDelta().processed());
            assertEquals(0, h.metricsDelta().failed());
            assertNull(h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "no lease is left behind");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            release.countDown();
            p.stop();
        }
    }

    private static java.util.List<String> List_of(String value) {
        return java.util.List.of(value);
    }
}
