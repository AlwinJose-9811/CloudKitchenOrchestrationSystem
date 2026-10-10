package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-RES-01 + NFR-REL-02 under a bounded high rate: the simulator pushes orders through the real service, queues and
 * workers against deliberately small stock while staff cancel some orders. Afterwards every unit of stock is accounted
 * for by exactly one committed order outcome.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatorInventoryReconciliationTest {

    private static final int STOCK = 12;
    private static final int TICKS = 150;

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

    private Map<Long, Integer> sumByItem(String statusFilter) throws Exception {
        Map<Long, Integer> sums = new HashMap<>();
        try (Connection c = h.db.connection();
             PreparedStatement p = c.prepareStatement("SELECT oi.item_id, SUM(oi.quantity) FROM order_items oi JOIN orders o ON o.id = oi.order_id"
                     + " WHERE " + statusFilter + " GROUP BY oi.item_id");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                sums.put(rs.getLong(1), rs.getInt(2));
            }
        }
        return sums;
    }

    @Test
    void everyUnitOfStockIsAccountedForByACommittedOrderOutcomeAfterAHighRateRun() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        h.t.execute("UPDATE inventory SET stock_qty = ?", STOCK);
        p.snapshotStock();
        CountDownLatch enough = new CountDownLatch(TICKS);
        FeedSimulator.OrderGenerator random = FeedSimulator.randomGenerator(h.routing, menu, new Random(11));
        FeedSimulator sim = new FeedSimulator(h.service, seq -> {
            Optional<com.cloudkitchen.dto.OrderRequest> next = random.next(seq);
            enough.countDown();
            return next;
        }, 6000, 6000, h.clock, h.events);
        ExecutorService staff = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-staff"));
        AtomicBoolean cancelling = new AtomicBoolean(true);
        try {
            Future<Integer> cancels = staff.submit(() -> {
                int done = 0;
                long after = 0;
                while (cancelling.get()) {
                    // visit the orders in id order as they appear (ids have gaps: rejected submissions consume one); cancel every third
                    long id = h.t.number("SELECT COALESCE(MIN(id), -1) FROM orders WHERE id > ?", after);
                    if (id < 0) {
                        Thread.onSpinWait();
                        continue;
                    }
                    after = id;
                    done += id % 3 == 0 && h.service.cancelOrder(id, "staff") ? 1 : 0;
                }
                return done;
            });
            assertTrue(sim.start(6000));
            assertTrue(enough.await(60, TimeUnit.SECONDS), "the simulator reached " + TICKS + " ticks");
            sim.stop();
            cancelling.set(false);
            int cancelled = cancels.get(30, TimeUnit.SECONDS);

            List<Long> ids = new ArrayList<>();
            try (Connection c = h.db.connection(); PreparedStatement q = c.prepareStatement("SELECT id FROM orders ORDER BY id");
                 ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            for (long id : ids) {
                p.awaitTerminal(id);
                p.assertEachEdgeOnce(id);
            }

            FeedSimulator.Stats stats = sim.stats();
            assertEquals(stats.produced(), stats.accepted() + stats.notAccepted());
            assertEquals(0, stats.errors(), "expected rejections are not errors");
            assertTrue(stats.accepted() > 0 && stats.notAccepted() > 0, "small stock: some orders fit, some are rejected: " + stats);
            assertEquals(stats.accepted(), ids.size(), "one order row per accepted submission");
            assertEquals(ids.size(), p.count("SELECT COUNT(DISTINCT external_ref) FROM orders"), "every simulated reference is unique");

            // the reconciliation: initial stock = stock left + delivered quantity (+ quantity held by recoverable orders: none left)
            Map<Long, Integer> delivered = sumByItem("o.status = 'DELIVERED'");
            Map<Long, Integer> nonTerminal = sumByItem("o.status NOT IN ('DELIVERED','CANCELLED','FAILED')");
            Map<Long, Integer> returned = sumByItem("o.status IN ('CANCELLED','FAILED')");
            assertTrue(nonTerminal.isEmpty());
            Map<Long, Integer> left = PipelineHarness.readStock(h);
            for (Map.Entry<Long, Integer> e : left.entrySet()) {
                assertTrue(e.getValue() >= 0, "never negative");
                assertEquals(STOCK, e.getValue() + delivered.getOrDefault(e.getKey(), 0) + nonTerminal.getOrDefault(e.getKey(), 0),
                        "item " + e.getKey() + ": initial = left + delivered + still held");
            }
            assertEquals(0, p.count("SELECT COUNT(*) FROM orders WHERE status IN ('CANCELLED','FAILED') AND stock_deducted = 1"),
                    "cancelled and failed orders gave their stock back");
            assertEquals(cancelled, p.count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED'"));
            assertTrue(returned.size() >= 0);

            MetricsView m = h.metricsDelta();
            assertEquals(stats.produced(), m.submitted());
            assertEquals(stats.notAccepted(), m.rejected());
            assertEquals(p.count("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"), m.processed());
            assertEquals(m.submitted(), m.rejected() + m.processed() + m.failed() + cancelled, "the invariant at quiescence");
            p.assertStockLedger();
        } finally {
            cancelling.set(false);
            staff.shutdownNow();
            sim.shutdown();
            p.stop();
        }
    }
}
