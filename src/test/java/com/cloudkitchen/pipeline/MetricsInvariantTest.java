package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.DashboardView;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.service.DashboardService;
import com.cloudkitchen.service.OrderQueryService;
import com.cloudkitchen.service.StockService;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.7: what each counter means, the invariant at quiescence, no double counting, and the dashboard snapshot. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsInvariantTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private void assertInvariant(PipelineHarness p) throws Exception {
        MetricsView m = h.metricsDelta();
        long cancelled = p.count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED'");
        long inFlight = p.count("SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED','FAILED')");
        assertEquals(m.submitted(), m.rejected() + m.processed() + m.failed() + cancelled + inFlight,
                "submitted = rejected + processed + failed + cancelled + inFlight: " + m + " cancelled=" + cancelled + " inFlight=" + inFlight);
        assertEquals(p.count("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"), m.processed());
        assertEquals(p.count("SELECT COUNT(*) FROM orders WHERE status = 'FAILED'"), m.failed());
    }

    @Test
    void theInvariantHoldsAfterAMixedRunOfEveryKindOfOutcome() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        PipelineHarness.Held held = p.holdAt("BURGER", WorkerHook.Stage.AFTER_POLL, null, null);
        try {
            for (int i = 0; i < 4; i++) {
                p.awaitDelivered(p.accepted("MI-OK" + i));                                    // processed
            }
            assertEquals(SubmissionResult.Outcome.REJECTED, p.submit(OrderSource.SWIGGY, "MI-BAD", "NO_SUCH", "Raita", 1).outcome()); // validation
            h.t.execute("UPDATE inventory SET stock_qty = 0 WHERE item_id = ?", h.t.itemId("Gulab Jamun"));
            p.snapshotStock();
            assertEquals(SubmissionResult.Outcome.REJECTED, p.submit(OrderSource.ZOMATO, "MI-STOCK", "BIRYANI", "Gulab Jamun", 1).outcome()); // stock
            long first = p.accepted("MI-DUP");
            p.awaitDelivered(first);
            assertEquals(SubmissionResult.Outcome.DUPLICATE, p.submit("MI-DUP").outcome());   // duplicate (counted as rejected)
            h.dispatcher.use(new FakeDispatchers.AlwaysFull(QueueFullException.Reason.QUEUE_FULL));
            assertEquals(SubmissionResult.Outcome.QUEUE_FULL, p.submit("MI-FULL").outcome()); // failed
            h.dispatcher.use(p.orch);
            // two orders stay in flight behind the held BURGER worker; a third is cancelled while it still can be
            p.submit(OrderSource.IN_HOUSE, "MI-HOLD1", "BURGER", "Classic Burger", 1);
            held.awaitBlocked();
            long waiting = p.submit(OrderSource.IN_HOUSE, "MI-HOLD2", "BURGER", "Classic Burger", 1).orderId();
            long toCancel = p.submit(OrderSource.IN_HOUSE, "MI-CANCEL", "BURGER", "Classic Burger", 1).orderId();
            assertTrue(h.service.cancelOrder(toCancel, "staff"));
            assertEquals("ROUTED", p.status(waiting));

            assertInvariant(p);
            MetricsView m = h.metricsDelta();
            assertEquals(12, m.submitted(), "every call counts once, including the rejected ones");
            assertEquals(3, m.rejected(), "validation, stock and duplicate; a queue-full order is a failure, not a rejection");
            assertEquals(5, m.processed());
            assertEquals(1, m.failed());
        } finally {
            held.release.countDown();
            p.stop();
        }
    }

    @Test
    void retriesAndDuplicateQueueEntriesAreNotCountedTwice() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withRetry(3, 1));
        try {
            long id = p.persistedAt("MI-RETRY", OrderStatus.ROUTED);
            h.orderFaults.failAlways("transition", PipelineHarness.outage());
            p.orch.enqueueRecovered(id, "BIRYANI");
            p.orch.enqueueRecovered(id, "BIRYANI");
            assertTrue(h.events.await(com.cloudkitchen.io.EventType.ORDER_STALLED, id, Duration.ofSeconds(20)), "retried and parked at least once");
            h.orderFaults.clearFaults();
            p.awaitDelivered(id);
            assertTrue(h.events.awaitUntil(PipelineHarness.WAIT, () -> p.orch.runtime("BIRYANI").size() == 0
                    && h.events.countFor(com.cloudkitchen.io.EventType.ORDER_SKIPPED_TERMINAL, id) >= 1));
            MetricsView m = h.metricsDelta();
            assertEquals(1, m.processed(), "several retries and two queue entries, one delivery, one count");
            assertEquals(1, m.processingSamples());
            assertEquals(0, m.failed());
        } finally {
            p.stop();
        }
    }

    @Test
    void theAverageIsZeroWithoutCompletionsAndTheSampleCountFollowsDeliveries() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withDelays(5, 5, 5));
        try {
            MetricsView none = new com.cloudkitchen.pipeline.MetricsRegistry().view();
            assertEquals(0, none.processingSamples());
            assertEquals(0.0, none.averageProcessingMillis(), "no division by zero");
            assertEquals(0, h.metricsDelta().processingSamples());
            for (int i = 0; i < 3; i++) {
                p.awaitDelivered(p.accepted("MI-AVG" + i));
            }
            MetricsView three = h.metricsDelta();
            assertEquals(3, three.processingSamples());
            assertTrue(h.metrics.view().averageProcessingMillis() > 0.0, "the shared registry now has samples: " + h.metrics.view());
        } finally {
            p.stop();
        }
    }

    @Test
    void theDashboardSnapshotReportsQueuesWorkersMetricsAndStaysImmutable() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(7, 50));
        try {
            AtomicBoolean recovering = new AtomicBoolean(true);
            DashboardService dashboard = new DashboardService(new OrderQueryService(h.db.database(), h.t.orders),
                    new StockService(h.db.database(), h.t.stock), h.service, p.orch::stationViews, () -> h.metrics.view(),
                    () -> false, () -> 30, recovering::get);
            h.t.execute("UPDATE inventory SET stock_qty = 5, reorder_level = 10 WHERE item_id = ?", h.t.itemId("Veg Biryani"));
            p.snapshotStock();
            p.orch.pause("PIZZA");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState("PIZZA") == WorkerState.PAUSED));
            long delivered = p.accepted("MI-DASH1");
            p.awaitDelivered(delivered);
            p.submit(OrderSource.SWIGGY, "MI-DASH2", "PIZZA", "Margherita", 1);       // waits in the paused station's queue
            long pending = p.pendingOrder("MI-DASH3");

            DashboardView before = dashboard.snapshot();
            assertTrue(before.recovering());
            assertEquals(3, before.stations().size());
            StationView pizza = before.stations().stream().filter(s -> s.stationCode().equals("PIZZA")).findFirst().orElseThrow();
            assertEquals(7, pizza.queueCapacity());
            assertEquals(1, pizza.queueSize());
            assertTrue(pizza.paused());
            assertEquals(WorkerState.PAUSED, pizza.worker().state());
            StationView biryani = before.stations().stream().filter(s -> s.stationCode().equals("BIRYANI")).findFirst().orElseThrow();
            assertEquals(1, biryani.worker().processedCount());
            assertEquals(1, before.metrics().processed());
            assertEquals(1, before.pendingCompensations());
            assertTrue(before.oldestPendingCompensationAgeSeconds() >= 0);
            assertEquals(30, before.simulatorRatePerMinute());
            assertTrue(before.lowStock().stream().anyMatch(r -> r.itemName().equals("Veg Biryani")));
            assertEquals(1L, before.statusCounts().get(OrderStatus.DELIVERED));
            assertThrows(UnsupportedOperationException.class, () -> before.stations().add(pizza));
            assertThrows(UnsupportedOperationException.class, () -> before.lowStock().clear());
            assertThrows(UnsupportedOperationException.class, () -> before.statusCounts().put(OrderStatus.PLACED, 9L));

            recovering.set(false);
            p.orch.resume("PIZZA");
            p.awaitDelivered(p.accepted("MI-DASH4"));
            DashboardView after = dashboard.snapshot();
            assertFalse(after.recovering());
            assertEquals(1, before.metrics().processed(), "the earlier snapshot did not change");
            assertEquals(1, pizza.queueSize(), "nor did the earlier station view");
            assertTrue(after.metrics().processed() >= 2);
            assertEquals(pending, h.service.findPendingCompensationIds().get(0));
        } finally {
            p.stop();
        }
    }
}
