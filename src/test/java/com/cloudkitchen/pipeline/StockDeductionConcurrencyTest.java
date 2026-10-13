package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** FR-INV-06 under load: many submitters compete for scarce stock through the real queue and workers; no oversell. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockDeductionConcurrencyTest {

    private static final int THREADS = 30;
    private static final int STOCK = 10;

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
    void competingSubmissionsNeverOversellAndEveryAcceptedOrderIsDelivered() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        ExecutorService executor = Executors.newFixedThreadPool(THREADS, new Factory());
        try {
            h.t.execute("UPDATE inventory SET stock_qty = ? WHERE item_id = ?", STOCK, h.t.itemId("Chicken Biryani"));
            p.snapshotStock();
            CyclicBarrier barrier = new CyclicBarrier(THREADS);
            List<Future<SubmissionResult>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String ref = "SD-" + i;
                futures.add(executor.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    return p.submit(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 1);
                }));
            }
            List<Long> accepted = new ArrayList<>();
            int insufficient = 0;
            for (Future<SubmissionResult> f : futures) {
                SubmissionResult r = f.get(60, TimeUnit.SECONDS);
                if (r.outcome() == SubmissionResult.Outcome.ACCEPTED) {
                    accepted.add(r.orderId());
                } else {
                    assertEquals(SubmissionResult.Outcome.REJECTED, r.outcome());
                    assertEquals(SubmissionResult.INSUFFICIENT_STOCK, r.reasonCode());
                    insufficient++;
                }
            }
            assertEquals(STOCK, accepted.size(), "exactly the available stock was sold");
            assertEquals(THREADS - STOCK, insufficient);
            for (long id : accepted) {
                p.awaitDelivered(id);
                p.assertEachEdgeOnce(id);
            }
            assertEquals(0, p.count("SELECT stock_qty FROM inventory WHERE item_id = ?", h.t.itemId("Chicken Biryani")), "sold out, never negative");
            assertEquals(STOCK, p.count("SELECT COUNT(*) FROM orders"), "rejected submissions left no order behind");
            assertEquals(THREADS, h.metricsDelta().submitted());
            assertEquals(THREADS - STOCK, h.metricsDelta().rejected());
            assertEquals(STOCK, h.metricsDelta().processed());
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static final class Factory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "test-submitter-" + n.incrementAndGet());
        }
    }
}
