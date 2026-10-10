package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.10.6 and RR-9: the startup order, exclusions, saturation and repetition of recovery. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveryServiceTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private RecoveryService recoveryOf(PipelineHarness p, RecoveryService.SnapshotStep step) {
        return new RecoveryService(h.service, p.orch, h.events, step);
    }

    @Test
    void theStepsRunInTheDocumentedOrderAndOnlyOrdinaryOrdersAreQueuedOldestFirst() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        long routedA = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "RS-A", "BIRYANI", "Chicken Biryani", 1)).orderId();
        // an order awaiting compensation: queue-full, and the restore keeps failing
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        h.stockFaults.failAlways("restore", new java.sql.SQLTransientConnectionException("outage", "08S01", 0));
        SubmissionResult pending = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.ZOMATO, "RS-P", "BIRYANI", "Raita", 2));
        assertFalse(pending.compensated());
        h.stockFaults.clearFaults();
        h.dispatcher.use(new FakeDispatchers.Accepting());
        long routedB = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.IN_HOUSE, "RS-B", "BIRYANI", "Chicken Biryani", 1)).orderId();
        long delivered = h.t.persistAt(h.t.biryaniOrder("RS-D"), OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING,
                OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);
        FencingToken stale = h.service.acquireLease(routedA, "OLD#1").orElseThrow(); // a lease left by the previous run
        assertEquals(1, stale.epoch());

        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            p.orch.pause("BIRYANI"); // keep queued ids queued so their order can be read
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState("BIRYANI") == WorkerState.PAUSED));
            List<String> steps = new ArrayList<>();
            RecoveryService recovery = recoveryOf(p, () -> {
                steps.add("snapshot");
                return 0;
            });
            recovery.recover();

            assertEquals(List.of("snapshot"), steps);
            assertNull(h.t.text("SELECT claimed_by FROM orders WHERE id = ?", routedA), "the stale lease was cleared");
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", routedA), "the fencing epoch was kept");
            assertEquals("FAILED", p.status(pending.orderId()), "the pending compensation was resolved before anything was queued");
            assertEquals(List.of(routedA, routedB), List.copyOf(p.orch.runtime("BIRYANI").queue()), "oldest first, without the pending and the delivered order");
            RecoveryService.Stats stats = recovery.stats();
            assertFalse(stats.recovering());
            assertEquals(2, stats.enqueued());
            assertEquals(1, stats.compensationsResolved());
            assertTrue(h.events.countFor(EventType.RECOVERY_STARTED, null) >= 1 && h.events.countFor(EventType.RECOVERY_COMPLETED, null) >= 1);

            p.orch.resume("BIRYANI");
            p.awaitDelivered(routedA);
            p.awaitDelivered(routedB);
            assertEquals("DELIVERED", p.status(delivered));
            p.assertEachEdgeOnce(routedA);
            p.assertEachEdgeOnce(routedB);
            p.assertEachEdgeOnce(pending.orderId());
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void anOrderOfAStationWithoutAWorkerIsFailedAndItsStockRestoredOnlyFromValidatedOrRouted() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        long routedPizza = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "RS-PZ1", "PIZZA", "Margherita", 1)).orderId();
        long packedPizza = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "RS-PZ2", "PIZZA", "Margherita", 1)).orderId();
        for (OrderStatus[] edge : new OrderStatus[][] {{OrderStatus.ROUTED, OrderStatus.PREPARING}, {OrderStatus.PREPARING, OrderStatus.PACKED}}) {
            h.service.transition(packedPizza, edge[0], edge[1], "TEST", null);
        }
        h.routing.refresh(h.db.database().withConnection(h.t.stations::findAllActive).stream().filter(s -> !s.getCode().equals("PIZZA")).toList());
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            RecoveryService recovery = recoveryOf(p, RecoveryService.SnapshotStep.NONE);
            recovery.recover();
            assertEquals("FAILED", p.status(routedPizza));
            assertEquals("STATION_UNAVAILABLE", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", routedPizza));
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", routedPizza), "ROUTED: the stock came back");
            assertEquals("PACKED", p.status(packedPizza), "the lifecycle has no edge PACKED -> FAILED, so the order is left as it is");
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", packedPizza));
            assertEquals(1, recovery.stats().failedStationUnavailable());
            assertEquals(1, recovery.stats().skippedUnavailable());
            p.assertStockLedger();
        } finally {
            h.routing.refresh(h.db.database().withConnection(h.t.stations::findAllActive));
            p.stop();
        }
    }

    @Test
    void runningRecoveryTwiceNeverDuplicatesAnyEffect() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "RS-R" + i, "BIRYANI", "Chicken Biryani", 1)).orderId());
        }
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            p.orch.pause("BIRYANI");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState("BIRYANI") == WorkerState.PAUSED));
            RecoveryService recovery = recoveryOf(p, RecoveryService.SnapshotStep.NONE);
            recovery.recover();
            recovery.recover(); // a second start finds the same orders: duplicate queue entries, no duplicate effect
            assertEquals(12, p.orch.runtime("BIRYANI").size());
            p.orch.resume("BIRYANI");
            for (long id : ids) {
                p.awaitDelivered(id);
                p.assertEachEdgeOnce(id);
            }
            assertTrue(h.events.awaitUntil(PipelineHarness.WAIT, () -> p.orch.runtime("BIRYANI").size() == 0
                    && h.events.countFor(EventType.ORDER_SKIPPED_TERMINAL, null) >= 6), "the duplicates were all skipped");
            assertEquals(6, h.metricsDelta().processed(), "processed once per order, not once per queue entry");
            assertEquals(0, h.metricsDelta().failed());
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void aSaturatedQueueMakesRecoveryWaitForTheWorkerInsteadOfDroppingOrders() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "RS-S" + i, "BIRYANI", "Chicken Biryani", 1)).orderId());
        }
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast().withQueue(1, 50), initial);
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_POLL, null, null);
        RecoveryService recovery = recoveryOf(p, RecoveryService.SnapshotStep.NONE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loader = new Thread(() -> {
            try {
                recovery.recover();
            } catch (Exception e) {
                failure.set(e);
            }
        }, "test-recovery");
        try {
            loader.start();
            held.awaitBlocked();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.orch.runtime("BIRYANI").size() == 1),
                    "one id in the single slot, the loader is waiting for room");
            assertTrue(recovery.isRecovering(), "recovery has not finished while the queue is saturated");
            held.release.countDown();
            loader.join(20_000);
            assertFalse(loader.isAlive());
            assertNull(failure.get());
            for (long id : ids) {
                p.awaitDelivered(id);
                p.assertEachEdgeOnce(id);
            }
            assertEquals(5, recovery.stats().enqueued());
            p.assertStockLedger();
        } finally {
            held.release.countDown();
            loader.join(5000);
            p.stop();
        }
    }

    @Test
    void anInterruptedRecoveryStopsCleanlyAndTheRemainingOrdersStayInTheDatabase() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        for (int i = 0; i < 3; i++) {
            h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "RS-I" + i, "BIRYANI", "Chicken Biryani", 1));
        }
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast().withQueue(1, 50), initial);
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_POLL, null, null);
        RecoveryService recovery = recoveryOf(p, RecoveryService.SnapshotStep.NONE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loader = new Thread(() -> {
            try {
                recovery.recover();
            } catch (Exception e) {
                failure.set(e);
                Thread.currentThread().interrupt();
            }
        }, "test-recovery-interrupted");
        try {
            loader.start();
            held.awaitBlocked();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.orch.runtime("BIRYANI").size() == 1));
            loader.interrupt();
            loader.join(20_000);
            assertFalse(loader.isAlive());
            assertTrue(failure.get() instanceof InterruptedException, "the interrupt ended recovery");
            assertFalse(recovery.isRecovering(), "recovery is over, not stuck");
            assertEquals(3, p.count("SELECT COUNT(*) FROM orders WHERE status = 'ROUTED' OR status = 'PREPARING' OR status = 'VALIDATED'"),
                    "nothing was lost or failed: the orders are still in the database for the next start");
        } finally {
            held.release.countDown();
            p.stop();
        }
    }
}
