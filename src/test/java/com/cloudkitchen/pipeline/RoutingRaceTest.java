package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** Scenarios 1 and 2a (SDD 7.8.4, 7.8.5): the worker dequeues before the submitter's ROUTED update; both may route. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingRaceTest {

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
    void aWorkerThatFinishesBeforeTheSubmitterRoutesLeavesOneRoutedEdgeAndAnAcceptedResult() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        FakeDispatchers.Gated gated = new FakeDispatchers.Gated(p.orch);
        h.dispatcher.use(gated);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-routing-submitter"));
        try {
            Future<SubmissionResult> submission = executor.submit(() -> p.submit("RACE-1"));
            org.junit.jupiter.api.Assertions.assertTrue(gated.dispatched.await(20, TimeUnit.SECONDS));
            long id = p.lastValidatedId();
            p.awaitDelivered(id); // the submitter is still held between the enqueue and its ROUTED update
            gated.release.countDown();
            SubmissionResult result = submission.get(20, TimeUnit.SECONDS);
            assertEquals(SubmissionResult.Outcome.ACCEPTED, result.outcome(), "RR-1/RR-2: a lost ROUTED race is benign");
            assertEquals(id, result.orderId());
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            assertEquals("worker:BIRYANI#1", h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            assertEquals(1, h.events.countFor(EventType.ORDER_ROUTED, id));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            gated.release.countDown();
            executor.shutdownNow();
            p.stop();
        }
    }

    @RepeatedTest(20)
    void submitterAndWorkerRoutingAtTheSameInstantProduceExactlyOneRoutedEdge() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        CyclicBarrier barrier = new CyclicBarrier(2);
        // the submitter waits after its enqueue; the worker waits just before leaving VALIDATED: both go on together
        h.hook.set(stage -> {
            if (stage == SubmissionHook.Stage.AFTER_OFFER) {
                meet(barrier);
            }
        });
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.BEFORE_STEP && step == OrderStatus.VALIDATED) {
                meet(barrier);
            }
        });
        try {
            long id = p.accepted("RACE-2");
            p.awaitDelivered(id);
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            assertEquals(1, h.events.countFor(EventType.ORDER_ROUTED, id), "the loser emitted nothing");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    private static void meet(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new AssertionError("the routing parties never met", e);
        }
    }
}
