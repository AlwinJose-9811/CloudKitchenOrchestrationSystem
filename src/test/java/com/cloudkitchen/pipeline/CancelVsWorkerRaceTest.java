package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Scenario 5b (SDD 7.8.8): staff cancel against a worker; whoever wins each conditional update decides. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelVsWorkerRaceTest {

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
    void aCancelThatWinsBeforeTheClaimRestoresTheStockAndTheWorkerStandsDown() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_LEASE, null, null);
            long id = p.accepted("CW-1");
            held.awaitBlocked();
            assertEquals(CancelOutcome.CANCELLED, h.service.cancelOrderDetailed(id, "staff"));
            held.release.countDown();
            assertTrue(h.events.await(EventType.ORDER_SKIPPED_TERMINAL, id, Duration.ofSeconds(20)), "the worker saw the terminal order");
            assertEquals("CANCELLED", p.status(id));
            assertEquals(0, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'PREPARING'", id));
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            assertNull(h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "the terminal step cleared the lease");
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            assertEquals(0, h.metricsDelta().processed());
            assertEquals(0, h.metricsDelta().failed());
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void aCancelAfterTheClaimIsRefusedAndTheOrderIsDeliveredWithItsStockKept() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_STEP, null, OrderStatus.ROUTED);
            long id = p.accepted("CW-2");
            held.awaitBlocked();
            assertEquals("PREPARING", p.status(id));
            assertEquals(CancelOutcome.NOT_CANCELLABLE, h.service.cancelOrderDetailed(id, "staff"));
            held.release.countDown();
            p.awaitDelivered(id);
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "no restore from PREPARING onwards");
            assertEquals(0, h.events.countFor(EventType.STOCK_RESTORED, id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @RepeatedTest(20)
    void aCancelRacingAnUnheldWorkerEndsConsistentlyWhicheverSideWins() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withDelays(1, 1, 1));
        try {
            long id = p.accepted("CW-3");
            CancelOutcome outcome = h.service.cancelOrderDetailed(id, "staff");
            p.awaitTerminal(id);
            String status = p.status(id);
            switch (outcome) {
                case CANCELLED -> {
                    assertEquals("CANCELLED", status);
                    assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
                    assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
                    assertEquals(0, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'DELIVERED'", id));
                }
                case NOT_CANCELLABLE -> {
                    assertEquals("DELIVERED", status);
                    assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
                    assertEquals(0, h.events.countFor(EventType.STOCK_RESTORED, id));
                }
                default -> throw new AssertionError("unexpected cancel outcome " + outcome);
            }
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }
}
