package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.service.SimulatorControl;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Roadmap 8.2, TESTING_STRATEGY "ThroughputSmokeTest", SRS NFR-PERF-01: sustained simulated load through the whole application
 * context, time-scaled by 10 (the step times of the default configuration, 1000, 300 and 700 ms, become 100, 30 and 70 ms, and
 * the order rate is multiplied by 10), so that 30 s of the test correspond to 5 minutes of real time.
 *
 * <p>Two things are shown. (1) The nominal load of NFR-PERF-01, 60 orders a minute (20 per station), is carried by the default
 * kitchens (2 s an order: 30 orders a minute per station, 90 in all): every order is delivered and <strong>no order is refused
 * or failed, in particular none for a full queue</strong>. (2) A load above the capacity of slower kitchens (step times of
 * 300, 100 and 400 ms scaled, the former defaults) with a small queue must degrade by compensation and never by loss: every order
 * is delivered or failed with its stock given back, and the stock ledger is exact. Test (2) does not assert that nothing is
 * refused: the arithmetic says some orders will be, and it prints what it saw.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThroughputSmokeTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private AppContext application(String... overrides) throws Exception {
        h.reset();
        h.t.execute("UPDATE inventory SET stock_qty = 100000, reorder_level = 10");
        Properties properties = AppFixture.properties(h.db, overrides);
        // time-scaled defaults (x10); the overload test passes the slower former defaults as overrides
        properties.setProperty("worker.prep.ms", "100");
        properties.setProperty("worker.pack.ms", "30");
        properties.setProperty("worker.delivery.ms", "70");
        for (int i = 0; i + 1 < overrides.length; i += 2) {
            properties.setProperty(overrides[i], overrides[i + 1]);
        }
        AppContext app = AppContext.create(AppConfig.fromProperties(properties), h.db.jdbcUrl(), Clock.systemUTC(), null, false);
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        return app;
    }

    /** Runs the simulator for {@code seconds} at {@code rate}, stops it and waits until every accepted order is terminal. */
    private SimulatorControl.Status feed(AppContext app, int rate, int seconds) throws Exception {
        assertTrue(app.simulator().start(rate));
        // wait for the event "the feed has produced what it would produce in that time", not for a duration
        long target = (long) rate * seconds / 60;
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(seconds * 4L),
                () -> app.simulator().status().produced() >= target), "the feed produced " + target + " orders");
        assertTrue(app.simulator().stop());
        SimulatorControl.Status fed = app.simulator().status();
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(120), () -> {
            try {
                return h.t.number("SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED','FAILED')") == 0;
            } catch (Exception e) {
                return false;
            }
        }), "every accepted order became terminal");
        return fed;
    }

    /** Metrics are counted after the commit (SDD 13.1), so the counter may trail the database row by a moment. */
    private void awaitMetrics(AppContext app, long processed, long failed) {
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), () -> {
            MetricsView view = app.metrics().view();
            return view.processed() == processed && view.failed() == failed;
        }), "the counters caught up with the database: " + app.metrics().view());
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @Test
    void theNominalLoadOfNfrPerf01IsCarriedWithoutAnyQueueFullFailureAndWithBoundedMemory() throws Exception {
        long heapBefore = usedHeapAfterGc();
        AppContext app = application(); // the default queue capacity of 50
        try {
            SimulatorControl.Status fed = feed(app, 600, 30); // 60 orders a minute in real time (NFR-PERF-01): two thirds of the capacity of 90
            long orders = h.t.number("SELECT COUNT(*) FROM orders");
            awaitMetrics(app, orders, 0);
            MetricsView metrics = app.metrics().view();
            System.out.println("THROUGHPUT-SMOKE sustainable: " + fed + " delivered=" + h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'")
                    + " metrics=" + metrics);
            assertTrue(fed.produced() >= 200, "the feed produced the nominal load: " + fed);
            assertEquals(fed.produced(), fed.accepted(), "nothing was refused: " + fed);
            assertEquals(0, fed.errors());
            assertEquals(orders, h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"), "all delivered");
            assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'FAILED'"), "no order failed, in particular none for a full queue");
            assertEquals(0, metrics.failed());
            assertEquals(0, metrics.rejected());
            assertEquals(orders, metrics.processed());
        } finally {
            app.close();
        }
        long growth = usedHeapAfterGc() - heapBefore;
        assertTrue(growth < 200L * 1024 * 1024, "memory stays bounded: heap grew by " + growth / 1024 / 1024 + " MB");
    }

    @Test
    void theNominalRateAboveTheCapacityDegradesByCompensationAndNeverByLoss() throws Exception {
        AppContext app = application("station.queue.capacity", "10", "worker.prep.ms", "300", "worker.pack.ms", "100", "worker.delivery.ms", "400");
        try {
            long stockBefore = h.t.number("SELECT SUM(stock_qty) FROM inventory");
            SimulatorControl.Status fed = feed(app, 600, 20); // 60 orders a minute in real time: 2.7 times the capacity of the slower kitchens
            long delivered = h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'");
            long failed = h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'FAILED'");
            long orders = h.t.number("SELECT COUNT(*) FROM orders");
            System.out.println("THROUGHPUT-SMOKE overload: " + fed + " orders=" + orders + " delivered=" + delivered + " failed(queue full)=" + failed
                    + " metrics=" + app.metrics().view());
            // the simulator waits for room in a full queue (the offer timeout), so overload also slows the feed down: it produces
            // fewer orders than the nominal rate would; the load is still far above what the kitchens can carry
            assertTrue(fed.produced() >= 60, "the feed produced a heavy load: " + fed);
            assertEquals(orders, delivered + failed, "every accepted order is delivered or failed: nothing is lost or stuck");
            assertEquals(failed, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE to_status = 'FAILED' AND note = 'QUEUE_FULL'"),
                    "an order fails only because its queue was full");
            assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'FAILED' AND stock_deducted <> 0"), "a failed order gave its stock back");
            long deliveredUnits = h.t.number("SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.status = 'DELIVERED'");
            assertEquals(stockBefore - deliveredUnits, h.t.number("SELECT SUM(stock_qty) FROM inventory"), "the stock ledger is exact");
            awaitMetrics(app, delivered, failed);
        } finally {
            app.close();
        }
    }
}
