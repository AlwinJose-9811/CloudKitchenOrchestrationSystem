package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.Launcher;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dao.jdbc.JdbcMenuItemDao;
import com.cloudkitchen.dao.jdbc.JdbcOrderDao;
import com.cloudkitchen.dao.jdbc.JdbcStationDao;
import com.cloudkitchen.dao.jdbc.JdbcStatusHistoryDao;
import com.cloudkitchen.dao.jdbc.JdbcStockDao;
import com.cloudkitchen.db.DataSourceFactory;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.OrderValidator;
import com.cloudkitchen.service.RoutingService;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestDatabase;
import com.cloudkitchen.io.NoOpOrderEventLog;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Phase 3 end-to-end path with nothing faked: real embedded Tomcat, real embedded MariaDB, schema and accounts
 * created by the start-up sequence, orders created through the real OrderService, read back by the servlet and JSP.
 */
class OrdersDemoWebTest {

    private static AppConfig config(Path root, String dbPassword, String hmac) {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("db.mode", "embedded");
        p.setProperty("db.name", "ckdb");
        p.setProperty("db.user", "ck_app");
        p.setProperty("db.password", dbPassword);
        p.setProperty("db.embedded.port", "0");
        p.setProperty("db.embedded.data.dir", root.resolve("db").toString());
        p.setProperty("db.embedded.base.dir", Path.of("data").toAbsolutePath().resolve("runtime").toString());
        p.setProperty("snapshot.hmac.secret", hmac);
        // Phase 6: this stack writes its order log, snapshots and reports into the test's own directory, never into ./logs or ./snapshots
        p.setProperty("log.orders.path", root.resolve("files").resolve("orders.log").toString());
        p.setProperty("snapshot.dir", root.resolve("files").resolve("snapshots").toString());
        p.setProperty("report.export.dir", root.resolve("files").resolve("reports").toString());
        p.setProperty("bootstrap.admin.username", "boss");
        p.setProperty("bootstrap.admin.password", "A" + TestDatabase.randomHex(10));
        p.setProperty("bootstrap.staff.username", "cook");
        p.setProperty("bootstrap.staff.password", "S" + TestDatabase.randomHex(10));
        return AppConfig.fromProperties(p);
    }

    /** A second, independent writer: the real OrderService on its own pool, as another part of the application would use it. */
    private static OrderService writer(AppConfig config, int dbPort, HikariDataSource[] poolOut) throws Exception {
        HikariDataSource pool = DataSourceFactory.create(config, DataSourceFactory.embeddedUrl("127.0.0.1", dbPort, "ckdb"));
        poolOut[0] = pool;
        Clock clock = Clock.systemUTC();
        Database database = new Database(pool, clock, 3, 5);
        RoutingService routing = new RoutingService();
        database.withConnection(c -> {
            routing.refresh(new JdbcStationDao().findAllActive(c));
            return null;
        });
        OrderService service = new OrderService(database, new JdbcOrderDao(clock, java.time.Duration.ofMillis(200)), new JdbcStockDao(clock),
                new JdbcStatusHistoryDao(), new JdbcMenuItemDao(clock), new OrderValidator(), routing, new FakeDispatchers.Accepting(),
                new InFlightRegistry(), new MetricsRegistry(), new NoOpOrderEventLog(), clock, SubmissionHook.NO_OP, OrderService.Settings.from(config));
        service.setAccepting(true);
        return service;
    }

    private static long itemId(Database database, String name) throws Exception {
        Long id = database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT id FROM menu_items WHERE name = ?")) {
                p.setString(1, name);
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        return id;
    }

    private static int rows(String html) {
        Matcher m = Pattern.compile("<tr>\\s*<td>").matcher(html);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    @Test
    void persistedOrdersAreShownByTheServletAndJspAndSurviveARestartOfTheWholeStack() throws Exception {
        Path root = Path.of("data").toAbsolutePath().resolve("test").resolve("web-" + TestDatabase.randomHex(4));
        String dbPassword = TestDatabase.randomHex(16);
        String hmac = TestDatabase.randomHex(32);
        AppConfig config = config(root, dbPassword, hmac);
        HikariDataSource[] pool = new HikariDataSource[1];
        int dbPort;
        try {
            // ---- first start: schema, reference data and accounts are created by the start-up sequence ----
            try (Launcher.RunningApp app = Launcher.start(config, true, root.resolve("tomcat"))) {
                dbPort = app.databasePort();
                HttpTestClient http = new HttpTestClient(app.port());

                Reply empty = http.get("/demo/orders");
                assertEquals(200, empty.status());
                assertTrue(empty.headers().firstValue("Content-Type").orElse("").contains("text/html"));
                assertTrue(empty.body().contains("Showing 0 of 0 orders"), empty.body());
                assertTrue(empty.body().contains("No orders."));
                assertEquals(0, rows(empty.body()));
                assertTrue(empty.body().contains("read-only"));

                OrderService service = writer(config, dbPort, pool);
                Database database = new Database(pool[0], Clock.systemUTC(), 3, 5);
                long chicken = itemId(database, "Chicken Biryani");
                long cola = itemId(database, "Cola");
                long margherita = itemId(database, "Margherita");
                SubmissionResult a = service.submitOrder(new OrderRequest(OrderSource.SWIGGY, "WEB-1", "BIRYANI", "Asha Rao", new BigDecimal("6.10"),
                        PaymentMode.PREPAID_AGGREGATOR, List.of(new OrderItemRequest(chicken, 2))));
                SubmissionResult b = service.submitOrder(new OrderRequest(OrderSource.ZOMATO, "WEB-2", "BURGER", "Bhavna Joshi", new BigDecimal("2.00"),
                        PaymentMode.PREPAID_AGGREGATOR, List.of(new OrderItemRequest(cola, 3))));
                SubmissionResult c = service.submitOrder(new OrderRequest(OrderSource.IN_HOUSE, "WEB-3", "PIZZA", "Chitra Menon", new BigDecimal("1.00"),
                        PaymentMode.CASH, List.of(new OrderItemRequest(margherita, 1))));
                assertEquals(List.of(true, true, true), List.of(a.orderId() != null, b.orderId() != null, c.orderId() != null));

                Reply all = http.get("/demo/orders");
                assertEquals(200, all.status());
                assertTrue(all.body().contains("Showing 3 of 3 orders"), all.body());
                assertEquals(3, rows(all.body()));
                for (String expected : List.of("WEB-1", "WEB-2", "WEB-3", "BIRYANI", "BURGER", "PIZZA", "SWIGGY", "ZOMATO", "IN_HOUSE", "498.00", "177.00", "299.00", "ROUTED")) {
                    assertTrue(all.body().contains(expected), expected);
                }
                for (String personal : List.of("Asha", "Bhavna", "Chitra")) {
                    assertFalse(all.body().contains(personal), "no customer data on the page");
                }
                assertTrue(all.body().indexOf("WEB-3") < all.body().indexOf("WEB-1"), "newest first");

                assertEquals(1, rows(http.get("/demo/orders?source=ZOMATO").body()));
                assertEquals(3, rows(http.get("/demo/orders?status=ROUTED").body()));
                assertTrue(http.get("/demo/orders?status=FAILED").body().contains("No orders."));
                assertEquals(0, rows(http.get("/demo/orders?status=FAILED").body()));

                // the page reflects the database, not a copy: change a row behind the application's back
                try (Connection raw = pool[0].getConnection(); PreparedStatement p = raw.prepareStatement("UPDATE orders SET status = 'CANCELLED' WHERE external_ref = 'WEB-2'")) {
                    p.executeUpdate();
                }
                assertEquals(1, rows(http.get("/demo/orders?status=CANCELLED").body()));
                assertEquals(2, rows(http.get("/demo/orders?status=ROUTED").body()));

                // output encoding: a hostile value in the data is escaped, never interpreted
                try (Connection raw = pool[0].getConnection(); PreparedStatement p = raw.prepareStatement("UPDATE stations SET code = ? WHERE code = 'PIZZA'")) {
                    p.setString(1, "X<b>&\"");
                    p.executeUpdate();
                }
                String escaped = http.get("/demo/orders").body();
                assertFalse(escaped.contains("<b>"), "raw markup from the database must not reach the page");
                assertTrue(escaped.contains("X&lt;b&gt;&amp;"), escaped);
                try (Connection raw = pool[0].getConnection(); PreparedStatement p = raw.prepareStatement("UPDATE stations SET code = 'PIZZA' WHERE code = ?")) {
                    p.setString(1, "X<b>&\"");
                    p.executeUpdate();
                }

                // bad input and other methods
                assertEquals(400, http.get("/demo/orders?page=abc").status());
                assertEquals(400, http.get("/demo/orders?page=0").status());
                assertEquals(400, http.get("/demo/orders?page=99999999999").status());
                assertEquals(400, http.get("/demo/orders?status=NOPE").status());
                assertEquals(400, http.get("/demo/orders?source=ebay").status());
                assertEquals(405, http.post("/demo/orders", "x=1").status());
                assertEquals(404, http.get("/demo/orders/extra").status());
                String error = http.get("/demo/orders?page=abc").body();
                assertFalse(error.contains("Exception") || error.contains("at com.cloudkitchen"), "no stack trace in an error page");

                // paging
                for (int i = 4; i <= 22; i++) {
                    service.submitOrder(new OrderRequest(OrderSource.SWIGGY, "WEB-" + i, "BIRYANI", "Dev Patel", new BigDecimal("1.00"),
                            PaymentMode.PREPAID_AGGREGATOR, List.of(new OrderItemRequest(chicken, 1))));
                }
                Reply page1 = http.get("/demo/orders");
                Reply page2 = http.get("/demo/orders?page=2");
                assertTrue(page1.body().contains("Showing 20 of 22 orders, page 1 of 2"), page1.body());
                assertEquals(20, rows(page1.body()));
                assertTrue(page1.body().contains("id=\"next\""));
                assertFalse(page1.body().contains("id=\"prev\""));
                assertEquals(2, rows(page2.body()));
                assertTrue(page2.body().contains("id=\"prev\""));
                assertFalse(page2.body().contains("id=\"next\""));
                assertEquals(0, rows(http.get("/demo/orders?page=3").body()));

                // the account bootstrap ran exactly once during this start
                try (Connection raw = pool[0].getConnection(); PreparedStatement p = raw.prepareStatement("SELECT COUNT(*) FROM staff_users"); ResultSet rs = p.executeQuery()) {
                    rs.next();
                    assertEquals(2, rs.getLong(1));
                }
                pool[0].close();
                pool[0] = null;
            }
            assertTrue(TestDatabase.serverProcessesForPort(dbPort).isEmpty(), "closing the application stops the embedded database");

            // ---- second start on the same data directory: nothing is re-seeded, the orders are still there ----
            try (Launcher.RunningApp app = Launcher.start(config, true, root.resolve("tomcat"))) {
                HttpTestClient http = new HttpTestClient(app.port());
                Reply again = http.get("/demo/orders");
                assertEquals(200, again.status());
                assertTrue(again.body().contains("Showing 20 of 22 orders, page 1 of 2"), "orders persisted across the restart");
                try (HikariDataSource check = DataSourceFactory.create(config, DataSourceFactory.embeddedUrl("127.0.0.1", app.databasePort(), "ckdb"));
                     Connection raw = check.getConnection();
                     PreparedStatement users = raw.prepareStatement("SELECT COUNT(*) FROM staff_users");
                     ResultSet u = users.executeQuery()) {
                    u.next();
                    assertEquals(2, u.getLong(1), "no second seeding of accounts");
                    try (PreparedStatement versions = raw.prepareStatement("SELECT COUNT(*) FROM schema_version"); ResultSet v = versions.executeQuery()) {
                        v.next();
                        assertEquals(2, v.getLong(1));
                    }
                    try (PreparedStatement stations = raw.prepareStatement("SELECT COUNT(*) FROM stations"); ResultSet s = stations.executeQuery()) {
                        s.next();
                        assertEquals(3, s.getLong(1));
                    }
                }
            }
        } finally {
            if (pool[0] != null) {
                pool[0].close();
            }
            TestDatabase.deleteTree(root);
        }
    }

    @Test
    void anUnreachableDatabaseStopsTheApplicationFromStartingWithAClearError() throws Exception {
        Path root = Path.of("data").toAbsolutePath().resolve("test").resolve("web-" + TestDatabase.randomHex(4));
        try {
            Properties p = new Properties();
            p.setProperty("server.port", "0");
            p.setProperty("db.mode", "external");
            p.setProperty("db.url", DataSourceFactory.embeddedUrl("127.0.0.1", TestDatabase.freePort(), "ckdb"));
            p.setProperty("db.user", "nobody");
            p.setProperty("db.password", TestDatabase.randomHex(16));
            p.setProperty("db.pool.connection.timeout.ms", "500");
            p.setProperty("snapshot.hmac.secret", TestDatabase.randomHex(32));
            AppConfig broken = AppConfig.fromProperties(p);
            var e = org.junit.jupiter.api.Assertions.assertThrows(com.cloudkitchen.exception.CloudKitchenException.class,
                    () -> Launcher.start(broken, false, root.resolve("tomcat")));
            assertEquals("WEBAPP_NOT_STARTED", e.getCode(), "the launcher reports a failed context instead of 'up'");
        } finally {
            TestDatabase.deleteTree(root);
        }
    }
}
