package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** Scenario 5c (SDD 7.8.8): queue-full compensation against a staff cancel; one terminal edge, stock restored once. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationRaceTest {

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
    void compensationAndCancelRacingFromValidatedProduceOneTerminalEdgeAndOneRestore() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicLong idRef = new AtomicLong();
        CountDownLatch idKnown = new CountDownLatch(1);
        CyclicBarrier barrier = new CyclicBarrier(2);
        // the dispatcher "fails" at the moment the cancel is released: both start from a committed VALIDATED order
        h.dispatcher.use((orderId, brand) -> {
            idRef.set(orderId);
            idKnown.countDown();
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new AssertionError("the racing parties never met", e);
            }
            throw new QueueFullException(brand, QueueFullException.Reason.QUEUE_FULL);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-compensation-race"));
        try {
            Future<SubmissionResult> submission = executor.submit(() -> p.submit("CR-1"));
            assertTrue(idKnown.await(20, TimeUnit.SECONDS));
            Future<CancelOutcome> cancel = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return h.service.cancelOrderDetailed(idRef.get(), "staff");
            });
            SubmissionResult result = submission.get(30, TimeUnit.SECONDS);
            CancelOutcome outcome = cancel.get(30, TimeUnit.SECONDS);
            long id = idRef.get();
            assertEquals(SubmissionResult.Outcome.QUEUE_FULL, result.outcome());
            String status = p.status(id);
            assertTrue(status.equals("FAILED") || status.equals("CANCELLED"), status);
            assertEquals(status.equals("CANCELLED") ? CancelOutcome.CANCELLED : CancelOutcome.NOT_CANCELLABLE, outcome);
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status IN ('FAILED','CANCELLED')", id),
                    "exactly one terminal edge");
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id), "the stock was restored exactly once");
            assertEquals(status.equals("FAILED") ? 1 : 0, h.metricsDelta().failed(), "failed++ only when the compensation won");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }
}
