package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Scenario 4 (SDD 7.8.7) with the real queue and workers: the failed enqueue is compensated exactly once. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueFullCompensationTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    /** Capacity 1, the worker held on a first order and the single queue slot taken by a second one. */
    private PipelineHarness.Held fillStation(PipelineHarness p, long[] accepted) throws Exception {
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_POLL, null, null);
        accepted[0] = p.accepted("QF-1");
        held.awaitBlocked();
        accepted[1] = p.accepted("QF-2");
        assertEquals(1, p.orch.runtime("BIRYANI").size());
        return held;
    }

    @Test
    void aFullQueueFailsTheOrderRestoresItsStockOnceAndTellsTheCaller() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(1, 50));
        long[] accepted = new long[2];
        PipelineHarness.Held held = fillStation(p, accepted);
        try {
            SubmissionResult result = p.submit("QF-3");
            assertEquals(SubmissionResult.Outcome.QUEUE_FULL, result.outcome());
            assertTrue(result.compensated(), "the failure is final and durable");
            long id = result.orderId();
            assertEquals("FAILED", p.status(id));
            assertEquals("VALIDATED->FAILED", p.edges(id).get(p.edges(id).size() - 1));
            assertEquals("QUEUE_FULL", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            assertEquals("SYSTEM", h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            assertEquals(1, h.events.countFor(EventType.QUEUE_FULL, id));
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            assertEquals(1, h.metricsDelta().failed());
            assertEquals(0, h.metricsDelta().rejected(), "the order existed, so it is a failure and not a rejection");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
            assertEquals(1, p.orch.runtime("BIRYANI").size(), "the failed order never entered the queue");
        } finally {
            held.release.countDown();
        }
        p.awaitDelivered(accepted[0]);
        p.awaitDelivered(accepted[1]);
        p.assertStockLedger();
        p.stop();
    }

    @Test
    void aSubmitterInterruptedWhileWaitingForRoomIsCompensatedWithTheInterruptedReason() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(1, 30_000));
        long[] accepted = new long[2];
        PipelineHarness.Held held = fillStation(p, accepted);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-interrupted-submitter"));
        try {
            Thread[] submitter = new Thread[1];
            Future<SubmissionResult> future = executor.submit(() -> {
                submitter[0] = Thread.currentThread();
                return p.submit("QF-INT");
            });
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), () -> {
                Thread t = submitter[0];
                if (t == null || t.getState() != Thread.State.TIMED_WAITING) {
                    return false;
                }
                for (StackTraceElement frame : t.getStackTrace()) {
                    if (frame.getClassName().endsWith("ArrayBlockingQueue") && frame.getMethodName().equals("offer")) {
                        return true;
                    }
                }
                return false;
            }), "the submitter is waiting inside the queue offer");
            submitter[0].interrupt();
            SubmissionResult result = future.get(20, TimeUnit.SECONDS);
            assertEquals(SubmissionResult.Outcome.QUEUE_FULL, result.outcome());
            assertTrue(result.compensated(), "compensation works with the interrupt flag restored");
            long id = result.orderId();
            assertEquals("FAILED", p.status(id));
            assertEquals("INTERRUPTED", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            held.release.countDown();
        }
        p.awaitDelivered(accepted[0]);
        p.awaitDelivered(accepted[1]);
        p.stop();
    }

    @Test
    void aClosedQueueAfterShutdownIsCompensatedWithTheClosedReason() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        p.stop();
        h.dispatcher.use(p.orch); // the stopped orchestrator is the dispatcher the service still holds
        SubmissionResult result = p.submit("QF-CLOSED");
        assertEquals(SubmissionResult.Outcome.QUEUE_FULL, result.outcome());
        assertTrue(result.compensated());
        assertEquals("QUEUE_CLOSED", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", result.orderId()));
        p.assertStockLedger();
    }

    @Test
    void whenTheCompensationItselfKeepsFailingTheOrderIsMarkedPendingAndLaterResolvedOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(1, 50));
        long[] accepted = new long[2];
        PipelineHarness.Held held = fillStation(p, accepted);
        try {
            h.stockFaults.failAlways("restore", new SQLTransientConnectionException("outage", "08S01", 0));
            SubmissionResult result = p.submit("QF-PENDING");
            assertEquals(SubmissionResult.Outcome.QUEUE_FULL, result.outcome());
            assertFalse(result.compensated(), "nothing was promised: the marker is durable but the order is not yet failed");
            long id = result.orderId();
            assertEquals("VALIDATED", p.status(id));
            assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "stock is still deducted, consistent with a live order");
            assertEquals(1, p.orch.runtime("BIRYANI").size(), "a pending order is never queued");

            h.stockFaults.clearFaults();
            assertTrue(h.service.completePendingCompensation(id));
            assertEquals("FAILED", p.status(id));
            assertEquals("RESOLVED", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertFalse(h.service.completePendingCompensation(id), "a second completion changes nothing");
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            held.release.countDown();
        }
        p.awaitDelivered(accepted[0]);
        p.awaitDelivered(accepted[1]);
        p.stop();
    }

    @Test
    void theOtherSourcesAreCompensatedTheSameWay() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(1, 50));
        long[] accepted = new long[2];
        PipelineHarness.Held held = fillStation(p, accepted);
        try {
            for (OrderSource source : new OrderSource[] {OrderSource.ZOMATO, OrderSource.IN_HOUSE}) {
                SubmissionResult r = p.submit(source, "QF-" + source, "BIRYANI", "Chicken Biryani", 1);
                assertEquals(SubmissionResult.Outcome.QUEUE_FULL, r.outcome());
                assertEquals(OrderStatus.FAILED.name(), p.status(r.orderId()));
            }
            p.assertStockLedger();
        } finally {
            held.release.countDown();
        }
        p.awaitDelivered(accepted[0]);
        p.stop();
    }
}
