package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.OrderValidator;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-ORD-07: the same (source, external_ref) submitted concurrently creates one order and deducts stock once. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateSubmissionConcurrencyTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private List<SubmissionResult> raceOn(ExecutorService executor, int threads, java.util.function.Function<Integer, OrderService> serviceOf,
                                          String ref) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<SubmissionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            OrderService service = serviceOf.apply(i);
            futures.add(executor.submit(() -> {
                barrier.await(20, TimeUnit.SECONDS);
                return service.submitOrder(h.t.request(OrderSource.ZOMATO, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1));
            }));
        }
        List<SubmissionResult> results = new ArrayList<>();
        for (Future<SubmissionResult> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        return results;
    }

    @RepeatedTest(20)
    void sixteenThreadsWithTheSameReferenceCreateOneOrderAndDeductOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        ExecutorService executor = Executors.newFixedThreadPool(16, r -> new Thread(r, "test-dup"));
        try {
            List<SubmissionResult> results = raceOn(executor, 16, i -> h.service, "ZO-DUP-1");
            assertEquals(1, results.stream().filter(r -> r.outcome() == SubmissionResult.Outcome.ACCEPTED).count());
            assertEquals(15, results.stream().filter(r -> r.outcome() == SubmissionResult.Outcome.DUPLICATE).count());
            long id = results.stream().filter(r -> r.outcome() == SubmissionResult.Outcome.ACCEPTED).findFirst().orElseThrow().orderId();
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
            assertEquals(1, p.count("SELECT COUNT(*) FROM orders WHERE external_ref = 'ZO-DUP-1'"));
            p.assertStockLedger();
            assertEquals(48, h.t.stockOf("Chicken Biryani"), "2 portions were deducted, once");
            assertEquals(49, h.t.stockOf("Raita"));
            assertEquals(16, h.metricsDelta().submitted());
            assertEquals(15, h.metricsDelta().rejected());
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }

    @RepeatedTest(20)
    void twoServicesWithSeparateInFlightGuardsAreStillStoppedByTheUniqueConstraint() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        ExecutorService executor = Executors.newFixedThreadPool(8, r -> new Thread(r, "test-dup2"));
        try {
            // a second "process": its own in-flight registry, so only the database can detect the duplicate
            OrderService other = new OrderService(h.db.database(), h.t.orders, h.t.stock, h.t.history, h.t.menu, new OrderValidator(),
                    h.routing, p.orch, new com.cloudkitchen.pipeline.InFlightRegistry(), new com.cloudkitchen.pipeline.MetricsRegistry(),
                    h.events, h.clock, SubmissionHook.NO_OP, OrderService.Settings.from(h.db.config()));
            other.setAccepting(true);
            List<SubmissionResult> results = raceOn(executor, 8, i -> i % 2 == 0 ? h.service : other, "ZO-DUP-2");
            assertEquals(1, results.stream().filter(r -> r.outcome() == SubmissionResult.Outcome.ACCEPTED).count());
            List<SubmissionResult> duplicates = results.stream().filter(r -> r.outcome() == SubmissionResult.Outcome.DUPLICATE).toList();
            assertEquals(7, duplicates.size());
            long id = results.stream().filter(r -> r.outcome() == SubmissionResult.Outcome.ACCEPTED).findFirst().orElseThrow().orderId();
            for (SubmissionResult d : duplicates) {
                if (d.orderId() != null) {
                    assertEquals(id, d.orderId(), "a database duplicate names the existing order");
                    assertNotNull(d.existingStatus());
                }
            }
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
            assertEquals(1, p.count("SELECT COUNT(*) FROM orders WHERE external_ref = 'ZO-DUP-2'"));
            assertEquals(48, h.t.stockOf("Chicken Biryani"));
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }

    @Test
    void aLaterResubmissionOfADeliveredOrderReportsTheExistingOrderAndItsStatus() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.accepted("SW-DUP-LATER");
            p.awaitDelivered(id);
            SubmissionResult again = p.submit("SW-DUP-LATER");
            assertEquals(SubmissionResult.Outcome.DUPLICATE, again.outcome());
            assertEquals(id, again.orderId());
            assertEquals(OrderStatus.DELIVERED, again.existingStatus());
            assertTrue(!again.compensationPending());
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
            assertEquals(49, h.t.stockOf("Chicken Biryani"));
        } finally {
            p.stop();
        }
    }
}
