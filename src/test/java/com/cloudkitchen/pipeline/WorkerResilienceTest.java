package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** A failure in one order never kills the worker (SDD 7.3, RR-8): the order is failed through the fenced path. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerResilienceTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static StationView biryani(PipelineHarness p) {
        return p.orch.stationViews().stream().filter(v -> v.stationCode().equals("BIRYANI")).findFirst().orElseThrow();
    }

    @Test
    void aPermanentErrorBeforeThePreparationFailsThatOrderRestoresItsStockAndTheWorkerCarriesOn() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            AtomicLong poison = new AtomicLong(-1);
            p.orch.setWorkerHook((stage, owner, id, step) -> {
                if (stage == WorkerHook.Stage.BEFORE_STEP && id == poison.get() && step == OrderStatus.ROUTED) {
                    throw new IllegalStateException("poisoned order");
                }
            });
            // hold the id back from the first order until the poison id is known: the order is created, then the hook arms
            long first = p.persistedAt("RES-POISON", OrderStatus.ROUTED);
            h.t.execute("UPDATE orders SET stock_deducted = 1 WHERE id = ?", first);
            h.t.execute("UPDATE inventory SET stock_qty = stock_qty - 2 WHERE item_id = ?", h.t.itemId("Chicken Biryani"));
            h.t.execute("UPDATE inventory SET stock_qty = stock_qty - 1 WHERE item_id = ?", h.t.itemId("Raita"));
            p.snapshotStock();
            h.t.execute("UPDATE inventory SET stock_qty = stock_qty + 2 WHERE item_id = ?", h.t.itemId("Chicken Biryani"));
            h.t.execute("UPDATE inventory SET stock_qty = stock_qty + 1 WHERE item_id = ?", h.t.itemId("Raita"));
            poison.set(first);
            p.orch.enqueueRecovered(first, "BIRYANI");
            assertEquals(EventType.ORDER_FAILED, p.awaitTerminal(first));

            assertEquals("FAILED", p.status(first));
            assertEquals("ROUTED->FAILED", p.edges(first).get(p.edges(first).size() - 1));
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", first), "stock was restored by the failing transaction");
            assertEquals(1, h.metricsDelta().failed());
            p.assertEachEdgeOnce(first);

            long next = p.accepted("RES-NEXT");
            p.awaitDelivered(next);
            assertEquals(1, biryani(p).worker().generation(), "the same worker generation handled both orders");
            assertEquals(0, biryani(p).worker().restarts());
        }
    }

    @Test
    void aFailureAfterPreparationStartedKeepsTheStockDeductedAndStillFailsTheOrder() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            AtomicLong poison = new AtomicLong(-1);
            p.orch.setWorkerHook((stage, owner, id, step) -> {
                if (stage == WorkerHook.Stage.BEFORE_STEP && id == poison.get() && step == OrderStatus.PREPARING) {
                    throw new IllegalStateException("poisoned while cooking");
                }
            });
            // accept through the service so that TX1 really deducts stock, but arm the hook first via a gate on the id
            long id = submitWithArmedPoison(p, poison);
            assertEquals(EventType.ORDER_FAILED, p.awaitTerminal(id));
            assertEquals("FAILED", p.status(id));
            assertEquals("PREPARING->FAILED", p.edges(id).get(p.edges(id).size() - 1));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "no restore from PREPARING onwards");
            p.assertStockLedger();
            long next = p.accepted("RES-AFTER-COOKING");
            p.awaitDelivered(next);
            assertTrue(biryani(p).worker().processedCount() >= 1);
        }
    }

    /** Submits one order whose id becomes the poison id before the worker reaches the cooking step. */
    private long submitWithArmedPoison(PipelineHarness p, AtomicLong poison) throws Exception {
        java.util.concurrent.CountDownLatch armed = new java.util.concurrent.CountDownLatch(1);
        h.hook.set(stage -> {
            if (stage == com.cloudkitchen.service.SubmissionHook.Stage.AFTER_TX1) {
                // the order id is the latest ORDER_VALIDATED event
                poison.set(h.events.all().stream().filter(e -> e.type() == EventType.ORDER_VALIDATED).reduce((a, b) -> b).orElseThrow().orderId());
                armed.countDown();
            }
        });
        long id = p.accepted("RES-COOKING");
        assertTrue(armed.await(10, java.util.concurrent.TimeUnit.SECONDS));
        h.hook.set(com.cloudkitchen.service.SubmissionHook.NO_OP);
        return id;
    }
}
