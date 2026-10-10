package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** RR-8 and scenario 2d: an outage never turns an order into FAILED and never drops it (invariant I-6). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransientFailureRecoveryTest {

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
    void anOutageParksTheOrderInsteadOfFailingItAndTheOrderIsDeliveredAfterwards() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withRetry(3, 1))) {
            long id = p.persistedAt("TR-1", OrderStatus.ROUTED);
            h.orderFaults.failAlways("transition", new SQLTransientConnectionException("connection lost", "08S01", 0));
            p.orch.enqueueRecovered(id, "BIRYANI");

            assertTrue(h.events.await(EventType.ORDER_STALLED, id, Duration.ofSeconds(20)), "the worker parked the order");
            assertEquals("ROUTED", p.status(id), "an outage does not move the order");
            assertEquals(0, h.metricsDelta().failed(), "an outage is never a FAILED order");
            assertEquals(3, p.edges(id).size());

            h.orderFaults.clearFaults();
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
            assertEquals(0, h.metricsDelta().failed());
        }
    }

    @Test
    void aFailingRoutedUpdateOfTheSubmitterIsBenignBecauseTheWorkerRoutesTheOrderItself() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            CountDownLatch polled = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            p.orch.setWorkerHook((stage, owner, id, step) -> {
                if (stage == WorkerHook.Stage.AFTER_POLL) {
                    polled.countDown();
                    try {
                        go.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            // call 1 is TX1's PLACED -> VALIDATED, call 2 is the submitter's VALIDATED -> ROUTED (the worker is held)
            h.orderFaults.failBefore("transition", 2, new SQLTransientConnectionException("blip", "08S01", 0));
            SubmissionResult result = p.submit("TR-2");
            assertEquals(SubmissionResult.Outcome.ACCEPTED, result.outcome(), "RR-1/RR-2: still accepted, no compensation");
            long id = result.orderId();
            assertTrue(polled.await(10, TimeUnit.SECONDS));
            assertEquals("VALIDATED", p.status(id), "the update failed, the id is queued and the worker holds it");
            go.countDown();
            p.awaitDelivered(id);
            assertEquals("worker:BIRYANI#1", h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
            assertEquals(0, h.metricsDelta().failed());
        }
    }
}
