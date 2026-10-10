package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Scenario 6a (SDD 7.8.9): the same id queued several times produces one chain and no repeated effect. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateQueueEntryTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @Test
    void threeEntriesForOneOrderDeliverItOnceAndTheOtherEntriesAreSkipped() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            // hold the worker so that all three entries are in the queue together
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
            p.orch.setWorkerHook((stage, owner, id, step) -> {
                if (stage == WorkerHook.Stage.AFTER_POLL && blocked.getCount() == 1) {
                    blocked.countDown();
                    await(release);
                }
            });
            long blocker = p.persistedAt("DQ-BLOCKER", OrderStatus.ROUTED);
            p.orch.enqueueRecovered(blocker, "BIRYANI");
            org.junit.jupiter.api.Assertions.assertTrue(blocked.await(10, java.util.concurrent.TimeUnit.SECONDS));

            long id = p.persistedAt("DQ-1", OrderStatus.ROUTED);
            for (int i = 0; i < 3; i++) {
                p.orch.enqueueRecovered(id, "BIRYANI");
            }
            assertEquals(3, p.orch.runtime("BIRYANI").size());
            release.countDown();

            p.awaitDelivered(id);
            assertEquals(2, awaitSkips(p, id), "the two later entries found a terminal order");
            assertEquals("DELIVERED", p.status(id));
            p.assertEachEdgeOnce(id);
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'DELIVERED'", id));
            assertEquals(2, h.metricsDelta().processed(), "the blocker and the order, each once");
            p.assertStockLedger();
        }
    }

    private static long awaitSkips(PipelineHarness p, long id) throws InterruptedException {
        p.h.events.awaitCount(EventType.ORDER_SKIPPED_TERMINAL, id, 2, Duration.ofSeconds(20));
        return p.h.events.countFor(EventType.ORDER_SKIPPED_TERMINAL, id);
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
