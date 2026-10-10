package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.db.Database;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.dto.SubmissionResult.Outcome;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.testsupport.FailingCommitDataSource;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.RecordingOrderEventLog;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** NFR-REL-05 / SDD 13.1.4: metrics and events appear only after the commit and only for the caller that changed a row. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostCommitSideEffectsTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @BeforeEach
    void clean() throws Exception {
        h.reset();
    }

    private long routed(String ref) throws Exception {
        long id = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1)).orderId();
        h.events.clear();
        h.orderFaults.resetCounters();
        h.historyFaults.resetCounters();
        h.stockFaults.resetCounters();
        return id;
    }

    @Test
    void aRolledBackTransitionEmitsNothing() throws Exception {
        long id = routed("PC-1");
        h.historyFaults.failAfter("insert", 1, new SQLTransientConnectionException("x"));
        assertThrows(PersistenceException.class, () -> h.service.failOrder(id, OrderStatus.ROUTED, "worker", "PROCESSING_ERROR"));
        assertEquals(List.of(), h.events.all());
        assertEquals(0, h.metricsDelta().failed());
    }

    @Test
    void aZeroRowConditionalUpdateEmitsNothingAndTheWinnerEmitsOnce() throws Exception {
        long id = routed("PC-2");
        assertTrue(h.service.failOrder(id, OrderStatus.ROUTED, "worker", "PROCESSING_ERROR"));
        assertFalse(h.service.failOrder(id, OrderStatus.ROUTED, "other", "PROCESSING_ERROR"), "somebody else already moved it");
        assertEquals(1, h.events.count(EventType.ORDER_FAILED));
        assertEquals(1, h.events.count(EventType.STOCK_RESTORED));
        assertEquals(1, h.metricsDelta().failed(), "no double count");
    }

    @Test
    void aFailedCommitEmitsNothingAndChangesNothing() throws Exception {
        long id = routed("PC-3");
        FailingCommitDataSource failing = new FailingCommitDataSource(h.db.pool());
        Database unreliable = new Database(failing, h.clock, 3, 1);
        RecordingOrderEventLog log = new RecordingOrderEventLog();
        MetricsRegistry metrics = new MetricsRegistry();
        OrderService service = new OrderService(unreliable, h.t.orders, h.t.stock, h.t.history, h.t.menu, new OrderValidator(), h.routing,
                new FakeDispatchers.Accepting(), new InFlightRegistry(), metrics, log, h.clock, SubmissionHook.NO_OP,
                OrderService.Settings.from(h.db.config()));
        service.setAccepting(true);
        failing.failNextCommits(1);
        assertThrows(PersistenceException.class, () -> service.failOrder(id, OrderStatus.ROUTED, "worker", "PROCESSING_ERROR"));
        assertEquals(List.of(), log.all(), "no event for a transaction that did not commit");
        assertEquals(0, metrics.view().failed());
        assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id));
        assertTrue(service.failOrder(id, OrderStatus.ROUTED, "worker", "PROCESSING_ERROR"), "the next attempt commits");
        assertEquals(1, metrics.view().failed());
    }

    @Test
    void aFailedCommitDuringSubmissionLeavesNoOrderNoEventAndNoDeduction() throws Exception {
        FailingCommitDataSource failing = new FailingCommitDataSource(h.db.pool());
        RecordingOrderEventLog log = new RecordingOrderEventLog();
        MetricsRegistry metrics = new MetricsRegistry();
        OrderService service = new OrderService(new Database(failing, h.clock, 3, 1), h.t.orders, h.t.stock, h.t.history, h.t.menu,
                new OrderValidator(), h.routing, new FakeDispatchers.Accepting(), new InFlightRegistry(), metrics, log, h.clock,
                SubmissionHook.NO_OP, OrderService.Settings.from(h.db.config()));
        service.setAccepting(true);
        failing.failNextCommits(1);
        SubmissionResult r = service.submitOrder(h.t.request(OrderSource.SWIGGY, "PC-4", "BIRYANI", "Chicken Biryani", 2));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertFalse(log.types().contains(EventType.ORDER_VALIDATED));
        assertEquals(1, metrics.view().submitted());
        assertEquals(1, metrics.view().rejected());
        assertEquals(0, metrics.view().failed());
    }

    @Test
    void theEventIsEmittedOnlyAfterTheChangeIsVisibleToOtherConnections() throws Exception {
        long id = routed("PC-5");
        List<String> visibleAtLogTime = new ArrayList<>();
        OrderEventLog checking = new RecordingOrderEventLog() {
            @Override
            public void log(EventType type, long orderId, String detail) {
                super.log(type, orderId, detail);
                record(type, orderId);
            }

            @Override
            public void log(EventType type, long orderId) {
                super.log(type, orderId);
                record(type, orderId);
            }

            private void record(EventType type, long orderId) {
                if (type == EventType.ORDER_CANCELLED) {
                    try {
                        visibleAtLogTime.add(h.t.text("SELECT status FROM orders WHERE id = ?", orderId));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        };
        OrderService service = new OrderService(h.db.database(), h.t.orders, h.t.stock, h.t.history, h.t.menu, new OrderValidator(), h.routing,
                new FakeDispatchers.Accepting(), new InFlightRegistry(), new MetricsRegistry(), checking, h.clock, SubmissionHook.NO_OP,
                OrderService.Settings.from(h.db.config()));
        assertTrue(service.cancelOrder(id, "staff"));
        assertEquals(List.of("CANCELLED"), visibleAtLogTime, "the committed state was already visible when the event was written");
    }

    @Test
    void theSubmittedCounterCountsAttemptsAndTheRejectedCounterOnlyRefusals() throws Exception {
        h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "PC-6", "BIRYANI", "Raita", 1));
        h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "PC-6", "BIRYANI", "Raita", 1));
        h.service.submitOrder(null);
        assertEquals(3, h.metricsDelta().submitted());
        assertEquals(2, h.metricsDelta().rejected());
        assertEquals(0, h.metricsDelta().failed());
    }
}
