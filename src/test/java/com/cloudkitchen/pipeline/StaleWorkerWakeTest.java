package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/**
 * RW-1 (SDD 7.11.13 timeline): worker A stalls holding epoch 1, the monitor retires it and worker B takes over with
 * epoch 2 and advances the order; when A finally wakes, every write it attempts changes nothing and A stands down.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaleWorkerWakeTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    /** Waits on a latch like a hung thread would: an interrupt does not wake it up. */
    static void hang(CountDownLatch latch) {
        while (true) {
            try {
                latch.await(30, TimeUnit.SECONDS);
                break;
            } catch (InterruptedException ignored) {
                // a zombie ignores the interrupt sent by cancel(true)
            }
        }
        Thread.interrupted();
    }

    @RepeatedTest(20)
    void aWorkerThatWakesAfterBeingReplacedChangesNothingAndStandsDown() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        CountDownLatch aBlocked = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);
        CountDownLatch bBlocked = new CountDownLatch(1);
        CountDownLatch bRelease = new CountDownLatch(1);
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage != WorkerHook.Stage.BEFORE_STEP) {
                return;
            }
            if (owner.equals("BIRYANI#1") && step == OrderStatus.PREPARING) {
                aBlocked.countDown();
                hang(aRelease);
            } else if (owner.equals("BIRYANI#2") && step == OrderStatus.PACKED) {
                bBlocked.countDown();
                hang(bRelease);
            }
        });
        try {
            long id = p.accepted("SWK-1");
            assertTrue(aBlocked.await(20, TimeUnit.SECONDS));
            assertEquals("PREPARING", p.status(id));
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id));
            StationWorker a = p.orch.runtime("BIRYANI").worker();

            p.advance(Duration.ofSeconds(70), "BIRYANI"); // beyond the lease TTL and the stall limit
            p.orch.monitor().runOnce();
            assertTrue(bBlocked.await(20, TimeUnit.SECONDS), "worker B resumed the order up to PACKED");
            assertEquals("PACKED", p.status(id));
            assertEquals(2, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "B holds the next epoch");
            assertTrue(a.isRetired());
            List<String> edgesBefore = p.edges(id);

            aRelease.countDown(); // A wakes up: its fenced step, renewal and release all find a newer epoch
            assertTrue(h.events.await(EventType.LEASE_LOST, id, Duration.ofSeconds(20)), "A noticed that it lost the order");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), a::hasExited), "and ended");
            assertEquals(edgesBefore, p.edges(id), "A wrote nothing");
            assertEquals("PACKED", p.status(id));

            bRelease.countDown();
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
            assertEquals("worker:BIRYANI#1", p.actors(id).get(3), "A claimed the order before it stalled");
            assertEquals("worker:BIRYANI#2", p.actors(id).get(4), "B moved it from PREPARING to PACKED");
            assertEquals(1, h.metricsDelta().processed(), "only B delivered");
            assertEquals(1, h.events.countFor(EventType.ORDER_PACKED, id));
            p.assertStockLedger();
        } finally {
            aRelease.countDown();
            bRelease.countDown();
            p.stop();
        }
    }
}
