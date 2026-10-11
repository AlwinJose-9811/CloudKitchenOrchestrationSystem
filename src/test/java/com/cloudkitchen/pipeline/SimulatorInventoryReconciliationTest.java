package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-RES-01 + NFR-REL-02 at a high rate with a deterministic outcome: the simulator pushes orders through the real
 * service and the real queues while the workers are paused and the queues are small, so that exactly the orders that
 * fit are accepted and every other one is compensated (queue full, stock restored). Staff then cancel every third
 * accepted order in id order; only afterwards the workers run. At each of the three points (orders held, orders
 * cancelled, everything terminal) every unit of stock is accounted for by exactly one order outcome, computed from the
 * order rows and independent of the {@code stock_deducted} flag the ledger uses. Repeated 20 times with a different seed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatorInventoryReconciliationTest {

    private static final int STOCK = 100;
    private static final int QUEUE = 4;
    private static final int TICKS = 120;
    private static final String NON_TERMINAL = "o.status NOT IN ('DELIVERED','CANCELLED','FAILED')";

    private ServiceHarness h;
    private MenuService menu;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
        menu = new MenuService(h.db.database(), h.t.menu, h.t.stock, h.t.stations);
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private Map<Long, Integer> sumByItem(String where) throws Exception {
        Map<Long, Integer> sums = new HashMap<>();
        try (Connection c = h.db.connection();
             PreparedStatement p = c.prepareStatement("SELECT oi.item_id, SUM(oi.quantity) FROM order_items oi JOIN orders o ON o.id = oi.order_id"
                     + " WHERE " + where + " GROUP BY oi.item_id");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                sums.put(rs.getLong(1), rs.getInt(2));
            }
        }
        return sums;
    }

    private static int total(Map<Long, Integer> sums) {
        return sums.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** initial stock = stock left + quantity of the orders that hold it (delivered or still non-terminal); never negative. */
    private void reconcile(String when, String holdingWhere) throws Exception {
        Map<Long, Integer> holding = sumByItem(holdingWhere);
        for (Map.Entry<Long, Integer> e : PipelineHarness.readStock(h).entrySet()) {
            assertTrue(e.getValue() >= 0, when + ": item " + e.getKey() + " is never negative");
            assertEquals(STOCK, e.getValue() + holding.getOrDefault(e.getKey(), 0), when + ": item " + e.getKey() + ": initial = left + held");
        }
    }

    private void assertInvariant(String when, MetricsView m, long cancelled, long inFlight) {
        assertEquals(m.submitted(), m.rejected() + m.processed() + m.failed() + cancelled + inFlight, when + ": " + m);
    }

    @RepeatedTest(20)
    void everyUnitOfStockIsAccountedForByAnOrderOutcomeWhileHeldAfterCancellationsAndAfterDelivery(RepetitionInfo repetition) throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(QUEUE, 5));
        h.t.execute("UPDATE inventory SET stock_qty = ?", STOCK);
        p.snapshotStock();
        List<String> stations = h.routing.stations().stream().map(com.cloudkitchen.domain.Station::getCode).toList();
        for (String code : stations) {
            p.orch.pause(code);
        }
        for (String code : stations) {
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState(code) == WorkerState.PAUSED), code + " is paused");
        }
        CountDownLatch enough = new CountDownLatch(TICKS);
        FeedSimulator.OrderGenerator random = FeedSimulator.randomGenerator(h.routing, menu, new Random(1000L + repetition.getCurrentRepetition()));
        FeedSimulator sim = new FeedSimulator(h.service, seq -> {
            Optional<com.cloudkitchen.dto.OrderRequest> next = random.next(seq);
            enough.countDown();
            return next;
        }, 6000, 6000, h.clock, h.events);
        try {
            // ---- phase 1: a high-rate run against paused workers and small queues ----
            assertTrue(sim.start(6000));
            assertTrue(enough.await(60, TimeUnit.SECONDS), "the simulator reached " + TICKS + " ticks");
            sim.stop();
            FeedSimulator.Stats stats = sim.stats();
            assertEquals(stats.produced(), stats.accepted() + stats.notAccepted());
            assertEquals(0, stats.errors());

            long held = p.count("SELECT COUNT(*) FROM orders WHERE status = 'ROUTED'");
            long failed = p.count("SELECT COUNT(*) FROM orders WHERE status = 'FAILED'");
            long queued = stations.stream().mapToLong(c -> p.orch.runtime(c).size()).sum();
            assertTrue(held > 0 && held == stats.accepted(), "the orders that fit were accepted: " + held + " / " + stats);
            assertEquals(queued, held, "exactly the orders that fit are queued, " + QUEUE + " per station at most");
            assertTrue(failed > 0, "the orders that did not fit were compensated: " + stats);
            assertEquals(held + failed, p.count("SELECT COUNT(*) FROM orders"), "one row per accepted or compensated submission");
            assertEquals(held + failed, p.count("SELECT COUNT(DISTINCT external_ref) FROM orders"), "every simulated reference is unique");
            assertEquals(0, p.count("SELECT COUNT(*) FROM orders WHERE status = 'FAILED' AND stock_deducted = 1"), "compensation restored the stock");
            assertTrue(total(sumByItem("o.status = 'FAILED'")) > 0, "the compensated orders did hold stock before they failed");

            // non-terminal orders hold their stock, compensated orders gave it back
            reconcile("held", NON_TERMINAL);
            MetricsView m = h.metricsDelta();
            assertEquals(stats.produced(), m.submitted());
            assertEquals(failed, m.failed());
            assertEquals(0, m.processed());
            assertInvariant("held", m, 0, held);

            // ---- phase 2: staff cancel every third held order, in id order (deterministic) ----
            List<Long> heldIds = new ArrayList<>();
            try (Connection c = h.db.connection(); PreparedStatement q = c.prepareStatement("SELECT id FROM orders WHERE status = 'ROUTED' ORDER BY id");
                 ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    heldIds.add(rs.getLong(1));
                }
            }
            int expectedCancelled = 0;
            for (int i = 0; i < heldIds.size(); i += 3) {
                assertTrue(h.service.cancelOrder(heldIds.get(i), "staff"), "order " + heldIds.get(i) + " is ROUTED and unleased, so it can be cancelled");
                expectedCancelled++;
            }
            assertTrue(expectedCancelled > 0, "cancellations really happened");
            assertEquals(expectedCancelled, p.count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED'"));
            assertEquals(0, p.count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED' AND stock_deducted = 1"), "cancelled orders gave their stock back");
            assertTrue(total(sumByItem("o.status = 'CANCELLED'")) > 0);
            reconcile("cancelled", NON_TERMINAL);
            long stillHeld = held - expectedCancelled;
            assertEquals(stillHeld, p.count("SELECT COUNT(*) FROM orders o WHERE " + NON_TERMINAL));
            assertInvariant("cancelled", h.metricsDelta(), expectedCancelled, stillHeld);

            // ---- phase 3: the workers run; the cancelled ids still in the queues are skipped ----
            for (String code : stations) {
                p.orch.resume(code);
            }
            for (long id : heldIds) {
                p.awaitTerminal(id);
            }
            assertTrue(PipelineHarness.awaitCondition(PipelineHarness.WAIT, () -> stations.stream().allMatch(c -> p.orch.runtime(c).size() == 0)),
                    "the queues are drained");
            assertEquals(0, p.count("SELECT COUNT(*) FROM orders o WHERE " + NON_TERMINAL));
            assertEquals(stillHeld, p.count("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"));
            reconcile("delivered", "o.status = 'DELIVERED'");
            for (long id : heldIds) {
                p.assertEachEdgeOnce(id);
            }
            MetricsView end = h.metricsDelta();
            assertEquals(stillHeld, end.processed(), "processed once per delivered order");
            assertEquals(failed, end.failed());
            assertInvariant("end", end, expectedCancelled, 0);
            assertFalse(end.processed() == 0, "something was delivered");
            p.assertStockLedger();
        } finally {
            sim.shutdown();
            p.stop();
        }
    }
}
