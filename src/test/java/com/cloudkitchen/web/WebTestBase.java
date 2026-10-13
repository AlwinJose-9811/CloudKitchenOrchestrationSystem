package com.cloudkitchen.web;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.TestApp;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/** One database fixture and one running application per test class (Lifecycle.PER_CLASS); subclasses only add tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class WebTestBase {

    protected DbFixture db;
    protected TestApp app;

    /** Extra configuration key/value pairs for the application of this class; none by default. */
    protected String[] overrides() {
        return new String[0];
    }

    @BeforeAll
    void startApplication() throws Exception {
        db = DbFixture.create(Clock.systemUTC());
        app = TestApp.start(db, overrides());
    }

    @AfterAll
    void stopApplication() {
        try {
            if (app != null) {
                app.close();
            }
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    // ---- helpers for tests that need orders, stock or a held pipeline ----

    /** Runs one update on the test database and returns the number of rows it changed. */
    protected int execute(String sql, Object... args) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            return p.executeUpdate();
        }
    }

    protected long itemId(String name) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("SELECT id FROM menu_items WHERE name = ?")) {
            p.setString(1, name);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no menu item " + name);
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * Puts an order into {@code status} with a history that is consistent with it. The domain refuses to load an order whose
     * history is not an unbroken chain ending in its status, so a test that forces a status must write that chain too.
     */
    protected void forceStatus(long orderId, String status, String compensationState) throws Exception {
        execute("DELETE FROM order_status_history WHERE order_id = ?", orderId);
        execute("UPDATE orders SET status = ?, compensation_state = ? WHERE id = ?", status, compensationState, orderId);
        String previous = null;
        for (String step : pathTo(status)) {
            execute("INSERT INTO order_status_history (order_id, from_status, to_status, changed_at, actor, note) VALUES (?, ?, ?, NOW(3), 'TEST', NULL)",
                    orderId, previous, step);
            previous = step;
        }
    }

    /** The legal chain from PLACED to {@code status}: the straight line of the lifecycle, or PLACED straight to CANCELLED or FAILED. */
    private static List<String> pathTo(String status) {
        if (status.equals("CANCELLED") || status.equals("FAILED")) {
            return List.of("PLACED", status);
        }
        List<String> line = List.of("PLACED", "VALIDATED", "ROUTED", "PREPARING", "PACKED", "OUT_FOR_DELIVERY", "DELIVERED");
        return line.subList(0, line.indexOf(status) + 1);
    }

    protected int stockOf(String itemName) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("SELECT stock_qty FROM inventory WHERE item_id = ?")) {
            p.setLong(1, itemId(itemName));
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    protected long count(String sql, Object... args) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    protected String text(String sql, Object... args) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * An order that is awaiting compensation: really submitted (so its stock really is deducted), then put into the state
     * the pipeline leaves it in when it could neither queue nor compensate it (VALIDATED, marker PENDING, one attempt, next
     * attempt in an hour). The workers must be paused by the caller.
     */
    protected long pendingOrder(String ref, String brand, String item, int quantity) throws Exception {
        long id = submit(OrderSource.SWIGGY, ref, brand, "Asha Rao", item, quantity);
        forceStatus(id, "VALIDATED", "PENDING");
        execute("UPDATE orders SET compensation_reason = 'QUEUE_FULL', compensation_attempts = 1,"
                + " compensation_next_at = DATE_ADD(NOW(3), INTERVAL 1 HOUR) WHERE id = ?", id);
        return id;
    }

    protected String statusOf(long orderId) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("SELECT status FROM orders WHERE id = ?")) {
            p.setLong(1, orderId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Submits one order of one line through the real {@code OrderService}; the order must be accepted. */
    protected long submit(OrderSource source, String ref, String brand, String customer, String itemName, int quantity) throws Exception {
        PaymentMode mode = source == OrderSource.IN_HOUSE ? PaymentMode.CASH : PaymentMode.PREPAID_AGGREGATOR;
        SubmissionResult result = app.services().orders().submitOrder(new OrderRequest(source, ref, brand, customer, new BigDecimal("3.00"), mode,
                List.of(new OrderItemRequest(itemId(itemName), quantity))));
        if (result.orderId() == null || result.outcome() != SubmissionResult.Outcome.ACCEPTED) {
            throw new AssertionError("order " + ref + " was not accepted: " + result);
        }
        return result.orderId();
    }

    protected void awaitStatus(long orderId, String status) {
        boolean reached = PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> {
            try {
                return status.equals(statusOf(orderId));
            } catch (Exception e) {
                return false;
            }
        });
        if (!reached) {
            throw new AssertionError("order " + orderId + " did not reach " + status);
        }
    }

    /**
     * Pauses every worker: accepted orders stay queued and no transition happens until {@link #resumeAll()}. A pause takes effect
     * at the next loop iteration of a worker (SDD 7.3: between orders), and a worker that was blocked in its queue poll when the
     * flag was set may still take one order, while its state still reads PAUSED from before an earlier resume. So this waits until
     * every worker is PAUSED <em>and</em> has been seen to start a new iteration (its heartbeat age dropped) after the flag was set.
     */
    protected void pauseAll() {
        var orchestrator = app.appContext().orchestrator();
        for (StationView station : orchestrator.stationViews()) {
            orchestrator.pause(station.stationCode());
        }
        java.util.Map<String, Long> lastAge = new java.util.HashMap<>();
        java.util.Set<String> looped = new java.util.HashSet<>();
        boolean allPaused = PipelineHarness.awaitCondition(Duration.ofSeconds(15), () -> {
            boolean paused = true;
            List<StationView> views = orchestrator.stationViews();
            for (StationView view : views) {
                long age = view.worker().heartbeatAgeMillis();
                Long before = lastAge.put(view.stationCode(), age);
                if (before != null && age < before) {
                    looped.add(view.stationCode());
                }
                paused &= view.worker().state() == com.cloudkitchen.domain.enums.WorkerState.PAUSED;
            }
            return paused && looped.size() == views.size();
        });
        if (!allPaused) {
            throw new AssertionError("the workers did not pause: " + orchestrator.stationViews());
        }
    }

    protected void resumeAll() {
        for (StationView station : app.appContext().orchestrator().stationViews()) {
            app.appContext().orchestrator().resume(station.stationCode());
        }
    }

    @AfterEach
    void releaseThePipeline() {
        resumeAll();
    }
}
