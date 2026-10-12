package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.service.SimulatorControl;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Roadmap 8.2, TESTING_STRATEGY "EndToEndLifecycleTest": the whole application context (real database, queues, workers, order
 * log, report) takes a simulated feed of all three sources to {@code DELIVERED} across all three stations, and the database, the
 * files and the metrics tell the same story: every order has exactly the seven lifecycle edges, nothing failed or was rejected,
 * stock left equals stock before minus what was ordered, the order log has one delivery per order, and the report has one row
 * per order. Capacity and stock are raised so that no order is refused for a reason other than the one under test.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndLifecycleTest {

    private static final int ORDERS = 120;

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
    void aSimulatedFeedOfAllThreeSourcesIsDeliveredAndTheDatabaseTheFilesAndTheMetricsAgree() throws Exception {
        h.reset();
        h.t.execute("UPDATE inventory SET stock_qty = 100000, reorder_level = 10");
        long stockBefore = h.t.number("SELECT SUM(stock_qty) FROM inventory");
        Properties properties = AppFixture.properties(h.db, "station.queue.capacity", "1000");
        Path orderLog = Path.of(properties.getProperty("log.orders.path"));
        AppContext app = AppContext.create(AppConfig.fromProperties(properties), h.db.jdbcUrl(), Clock.systemUTC(), null, false);
        try {
            assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
            assertTrue(app.simulator().start(600), "the simulator starts");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> app.simulator().status().produced() >= ORDERS),
                    "the simulator produced " + ORDERS + " orders: " + app.simulator().status());
            assertTrue(app.simulator().stop());
            SimulatorControl.Status fed = app.simulator().status();
            assertEquals(fed.produced(), fed.accepted() + fed.notAccepted());
            assertEquals(0, fed.notAccepted(), "stock and queues were sized so that every order is accepted: " + fed);
            assertEquals(0, fed.errors());

            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
                try {
                    return h.t.number("SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED','FAILED')") == 0
                            && h.t.number("SELECT COUNT(*) FROM orders") == fed.accepted();
                } catch (Exception e) {
                    return false;
                }
            }), "every accepted order reached a terminal state");

            // the database
            long orders = h.t.number("SELECT COUNT(*) FROM orders");
            assertEquals(fed.accepted(), orders);
            assertEquals(orders, h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"), "all delivered");
            for (String source : List.of("SWIGGY", "ZOMATO", "IN_HOUSE")) {
                assertTrue(h.t.number("SELECT COUNT(*) FROM orders WHERE source = ?", source) > 0, "orders from " + source);
            }
            assertEquals(3, h.t.number("SELECT COUNT(DISTINCT station_id) FROM orders"), "all three stations worked");
            assertEquals(0, h.t.number("SELECT COUNT(*) FROM (SELECT order_id FROM order_status_history GROUP BY order_id HAVING COUNT(*) <> 7) x"),
                    "every order has exactly seven history rows");
            assertEquals(7, h.t.number("SELECT COUNT(DISTINCT CONCAT(COALESCE(from_status,'-'), '>', to_status)) FROM order_status_history"),
                    "and they are the seven edges of the straight lifecycle");
            assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders WHERE stock_deducted <> 1"), "stock stays deducted for delivered orders");
            long ordered = h.t.number("SELECT SUM(quantity) FROM order_items");
            assertEquals(stockBefore - ordered, h.t.number("SELECT SUM(stock_qty) FROM inventory"), "stock before - units ordered = stock now");

            // the metrics
            MetricsView metrics = app.metrics().view();
            assertEquals(fed.produced(), metrics.submitted());
            assertEquals(orders, metrics.processed());
            assertEquals(0, metrics.failed());
            assertEquals(0, metrics.rejected());

            // the report
            Path report = app.reports().exportDaily(LocalDate.now(ZoneOffset.UTC));
            List<String> csv = Files.readAllLines(report, StandardCharsets.UTF_8);
            assertEquals(orders + 1, csv.size(), "header + one row per order");
            assertTrue(csv.stream().skip(1).allMatch(l -> l.contains(",DELIVERED,")));
        } finally {
            app.close();
        }

        // the order log, complete once the application has closed it
        List<String> log = Files.readAllLines(orderLog, StandardCharsets.UTF_8);
        long delivered = h.t.number("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'");
        assertEquals(delivered, log.stream().filter(l -> l.contains("event=ORDER_DELIVERED")).count(), "one delivery event per order");
        assertEquals(delivered, log.stream().filter(l -> l.contains("event=ORDER_VALIDATED")).count());
        assertFalse(log.stream().anyMatch(l -> l.contains("event=ORDER_FAILED") || l.contains("event=ORDER_REJECTED") || l.contains("event=QUEUE_FULL")));
        assertFalse(log.stream().anyMatch(l -> l.contains("Asha") || l.contains("customer")), "no customer data in the order log");
    }
}
