package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedCrash;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * SDD 7.8.6, windows W1-W4 with a real restart: the submitter or the whole process stops at an exact point, a fresh
 * orchestrator starts on the same database and runs recovery. Stock is deducted once, or restored once, in every case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingCrashWindowTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private void crashAt(SubmissionHook.Stage stage, PipelineHarness p, String ref) {
        h.hook.set(s -> {
            if (s == stage) {
                throw new SimulatedCrash(stage.name());
            }
        });
        try {
            assertThrows(SimulatedCrash.class, () -> p.submit(ref));
        } finally {
            h.hook.set(SubmissionHook.NO_OP);
        }
    }

    private long afterCrashDelivers(SubmissionHook.Stage stage, String ref) throws Exception {
        PipelineHarness first = PipelineHarness.start(h, PipelineHarness.fast().withDelays(0, 0, 0));
        // the first process has no worker for BIRYANI that could win the race: hold it so that "the process died" is exact
        PipelineHarness.Held held = first.holdAt("BIRYANI", WorkerHook.Stage.AFTER_POLL, null, null);
        crashAt(stage, first, ref);
        long id = first.lastValidatedId();
        if (stage != SubmissionHook.Stage.AFTER_TX1) {
            held.awaitBlocked(); // the worker polled the id and is stuck: the process dies with the id in nobody's hands
        }
        first.crash();
        held.release.countDown();
        PipelineHarness second = first.restart(PipelineHarness.fast());
        try {
            new RecoveryService(h.service, second.orch, h.events, RecoveryService.SnapshotStep.NONE).recover();
            second.awaitDelivered(id);
            second.assertEachEdgeOnce(id);
            assertEquals(1, second.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "deducted once at TX1, never again");
            assertEquals(1, second.count("SELECT COUNT(*) FROM orders WHERE external_ref = ?", ref));
            second.assertStockLedger();
            return id;
        } finally {
            second.stop();
        }
    }

    @Test
    void w1ADeathAfterCommitBeforeTheOfferLeavesAnOrderThatTheNextRunDelivers() throws Exception {
        afterCrashDelivers(SubmissionHook.Stage.AFTER_TX1, "RC-W1");
    }

    @Test
    void w2ADeathAfterTheOfferLosesTheQueueButNotTheOrder() throws Exception {
        afterCrashDelivers(SubmissionHook.Stage.AFTER_OFFER, "RC-W2");
    }

    @Test
    void w3ADeathAfterTheRoutedCommitResumesFromRouted() throws Exception {
        long id = afterCrashDelivers(SubmissionHook.Stage.AFTER_ROUTED, "RC-W3");
        assertEquals(1, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
    }

    @Test
    void w4ADeathDuringCompensationLeavesAPendingMarkerThatTheNextRunResolvesBeforeQueueingAnything() throws Exception {
        PipelineHarness first = PipelineHarness.start(h, PipelineHarness.fast());
        h.dispatcher.use(new FakeDispatchers.AlwaysFull(QueueFullException.Reason.QUEUE_FULL));
        h.stockFaults.failAlways("restore", new SQLTransientConnectionException("outage", "08S01", 0));
        SubmissionResult result = first.submit("RC-W4");
        assertEquals(SubmissionResult.Outcome.QUEUE_FULL, result.outcome());
        long id = result.orderId();
        assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
        first.crash(); // the process dies with the compensation unfinished
        h.stockFaults.clearFaults();
        PipelineHarness second = first.restart(PipelineHarness.fast());
        try {
            RecoveryService recovery = new RecoveryService(h.service, second.orch, h.events, RecoveryService.SnapshotStep.NONE);
            recovery.recover();
            assertEquals("FAILED", second.status(id), "resolved before any enqueue");
            assertEquals("RESOLVED", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertEquals(0, second.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            assertEquals(0, recovery.stats().enqueued(), "a pending order is never enqueued");
            second.assertEachEdgeOnce(id);
            second.assertStockLedger();
            assertTrue(second.orch.runtime("BIRYANI").queue().isEmpty());
        } finally {
            second.stop();
        }
    }

    @Test
    void anAcceptedOrderOfEveryStageSurvivesTheSameCrashTogether() throws Exception {
        PipelineHarness first = PipelineHarness.start(h, PipelineHarness.fast().withDelays(0, 0, 0));
        PipelineHarness.Held held = first.holdAt("BIRYANI", WorkerHook.Stage.AFTER_POLL, null, null);
        long a = first.accepted("RC-ALL-A");           // polled by the held worker
        held.awaitBlocked();
        long b = first.accepted("RC-ALL-B");           // queued
        long c = first.submit(OrderSource.ZOMATO, "RC-ALL-C", "BURGER", "Classic Burger", 1).orderId(); // another station, delivered
        first.awaitDelivered(c);
        first.crash();
        held.release.countDown();
        PipelineHarness second = first.restart(PipelineHarness.fast());
        try {
            new RecoveryService(h.service, second.orch, h.events, RecoveryService.SnapshotStep.NONE).recover();
            second.awaitDelivered(a);
            second.awaitDelivered(b);
            for (long id : new long[] {a, b, c}) {
                second.assertEachEdgeOnce(id);
            }
            second.assertStockLedger();
        } finally {
            second.stop();
        }
    }
}
