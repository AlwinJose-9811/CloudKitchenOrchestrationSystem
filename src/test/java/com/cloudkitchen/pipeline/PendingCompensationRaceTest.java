package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
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

/** I-12 and RW-10: an order awaiting compensation is never leased, routed or processed, however the writes interleave. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingCompensationRaceTest {

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
    void aMarkerWrittenWhileAWorkerHoldsTheLeaseStopsTheWorkerBeforeItRoutes() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.persistedAt("PC-1", OrderStatus.VALIDATED);
            PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_LEASE, id, null);
            p.orch.enqueueRecovered(id, "BIRYANI");
            held.awaitBlocked();
            assertTrue(h.t.ok(c -> h.t.orders.markCompensationPending(c, id, "QUEUE_FULL", h.clock.instant().plusSeconds(60))));
            held.release.countDown();
            assertTrue(h.events.await(EventType.ORDER_SKIPPED_PENDING_COMPENSATION, id, Duration.ofSeconds(20)));
            assertEquals("VALIDATED", p.status(id));
            assertEquals(0, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> {
                try {
                    return h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id) == null;
                } catch (java.sql.SQLException e) {
                    return false;
                }
            }), "the worker released its lease");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @RepeatedTest(20)
    void markerAndLeaseAcquisitionRacingNeverLeaveAPendingOrderProcessed() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-pending-race"));
        try {
            long id = p.persistedAt("PC-2", OrderStatus.VALIDATED);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> marker = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return h.t.ok(c -> h.t.orders.markCompensationPending(c, id, "QUEUE_FULL", h.clock.instant().plusSeconds(60)));
            });
            Future<?> enqueue = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                p.orch.enqueueRecovered(id, "BIRYANI");
                return null;
            });
            boolean marked = marker.get(30, TimeUnit.SECONDS);
            enqueue.get(30, TimeUnit.SECONDS);
            assertTrue(h.events.awaitUntil(Duration.ofSeconds(20), () -> h.events.countFor(EventType.ORDER_DELIVERED, id) > 0
                    || h.events.countFor(EventType.ORDER_SKIPPED_PENDING_COMPENSATION, id) > 0));
            if (marked) {
                assertEquals("VALIDATED", p.status(id), "a pending order stays where it is");
                assertEquals(0, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
                assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            } else {
                assertNull(h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id), "the marker lost: the order is ordinary");
                p.awaitDelivered(id);
            }
            assertFalse(h.events.countFor(EventType.ORDER_DELIVERED, id) > 0 && marked, "never both pending and delivered");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }
}
