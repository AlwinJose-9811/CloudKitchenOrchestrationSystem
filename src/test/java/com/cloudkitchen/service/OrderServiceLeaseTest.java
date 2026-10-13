package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dao.jdbc.JdbcOrderDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.db.LeaseStampHook;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.exception.LeaseStampStaleException;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.RecordingOrderEventLog;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The owner-fenced API (SDD 7.11): leases, fenced steps, the first-statement rule and the stale-stamp retry. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceLeaseTest {

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
        h.orderFaults.resetCounters();
        h.historyFaults.resetCounters();
        h.stockFaults.resetCounters();
        h.events.clear();
        return id;
    }

    @Test
    void theOwnerWalksTheWholeLifecycleWithFencedStepsAndWorkerActors() throws Exception {
        long id = routed("LS-1");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        assertEquals(1, token.epoch());
        OrderStatus[] path = {OrderStatus.PREPARING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED};
        OrderStatus from = OrderStatus.ROUTED;
        for (OrderStatus to : path) {
            assertTrue(h.service.transitionAsOwner(token, from, to, null), from + " -> " + to);
            from = to;
        }
        assertEquals("DELIVERED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals(null, h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "a terminal step releases the lease");
        assertEquals(4, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND actor = 'worker:BIRYANI#1'", id));
        assertEquals(48, h.t.stockOf("Chicken Biryani"), "delivered orders keep their stock consumed");
        assertEquals(1, h.events.count(com.cloudkitchen.io.EventType.ORDER_DELIVERED));
    }

    @Test
    void aSupersededOwnerCanDoNothingAtAll() throws Exception {
        long id = routed("LS-2");
        FencingToken old = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        h.clock.advance(Duration.ofSeconds(31));
        FencingToken fresh = h.service.acquireLease(id, "BIRYANI#2").orElseThrow();
        assertEquals(2, fresh.epoch());
        h.orderFaults.resetCounters();
        h.historyFaults.resetCounters();
        h.stockFaults.resetCounters();
        assertFalse(h.service.transitionAsOwner(old, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
        assertFalse(h.service.failAsOwner(old, OrderStatus.ROUTED, "PROCESSING_ERROR"));
        assertFalse(h.service.renewLease(old));
        h.service.releaseLease(old); // a no-op: it must not free the new owner's lease
        assertEquals("BIRYANI#2", h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id));
        assertEquals(List.of(), h.historyFaults.sequence(), "F-2: the stale owner wrote no history");
        assertEquals(List.of(), h.stockFaults.sequence(), "and touched no inventory");
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
        assertTrue(h.service.transitionAsOwner(fresh, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
    }

    @Test
    void failAsOwnerRestoresOnlyFromValidatedOrRouted() throws Exception {
        long routed = routed("LS-3");
        FencingToken t1 = h.service.acquireLease(routed, "BIRYANI#1").orElseThrow();
        assertTrue(h.service.failAsOwner(t1, OrderStatus.ROUTED, "PROCESSING_ERROR"));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(1, h.metricsDelta().failed());

        long preparing = routed("LS-4");
        FencingToken t2 = h.service.acquireLease(preparing, "BIRYANI#1").orElseThrow();
        assertTrue(h.service.transitionAsOwner(t2, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
        assertTrue(h.service.failAsOwner(t2, OrderStatus.PREPARING, "PROCESSING_ERROR"));
        assertEquals(48, h.t.stockOf("Chicken Biryani"), "no restore from PREPARING");
        assertEquals("1", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", preparing));
    }

    @Test
    void anIllegalEdgeIsRefusedBeforeAnyStatement() throws Exception {
        long id = routed("LS-5");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        h.orderFaults.resetCounters();
        assertThrows(com.cloudkitchen.exception.InvalidStatusTransitionException.class,
                () -> h.service.transitionAsOwner(token, OrderStatus.ROUTED, OrderStatus.DELIVERED, null));
        assertEquals(List.of(), h.orderFaults.sequence());
    }

    @Test
    void releaseAndReacquireGivesTheNextEpochAndStartupClearsLeasesButKeepsEpochs() throws Exception {
        long a = routed("LS-6");
        long b = routed("LS-7");
        FencingToken first = h.service.acquireLease(a, "BIRYANI#1").orElseThrow();
        h.service.acquireLease(b, "BIRYANI#1").orElseThrow();
        h.service.releaseLease(first);
        assertEquals(2, h.service.acquireLease(a, "BIRYANI#2").orElseThrow().epoch());
        assertEquals(List.of(b), h.service.reclaimLeasesOf("BIRYANI#1"), "only the exact retired owner string");
        assertEquals(1, h.service.clearAllLeases());
        assertEquals(3, h.service.acquireLease(a, "BIRYANI#3").orElseThrow().epoch(), "epochs survive the restart");
    }

    // ---- stale-stamp protocol (S-5, S-6) ----

    private OrderService serviceWith(LeaseStampHook hook) throws Exception {
        Database database = new Database(h.db.pool(), h.clock, 3, 1, hook);
        JdbcOrderDao dao = new JdbcOrderDao(h.clock, DbTestSupport.MARGIN, hook);
        OrderService service = new OrderService(database, dao, h.t.stock, h.t.history, h.t.menu, new OrderValidator(), h.routing,
                new FakeDispatchers.Accepting(), new InFlightRegistry(), new MetricsRegistry(), new RecordingOrderEventLog(), h.clock,
                SubmissionHook.NO_OP, OrderService.Settings.from(h.db.config()));
        service.setAccepting(true);
        return service;
    }

    @Test
    void aSlowCommitIsRolledBackAndRetriedWithAFreshStamp() throws Exception {
        long id = routed("LS-8");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        AtomicInteger beforeCommits = new AtomicInteger();
        OrderService slow = serviceWith(phase -> {
            if (phase == LeaseStampHook.Phase.BEFORE_COMMIT && beforeCommits.incrementAndGet() == 1) {
                h.clock.advance(Duration.ofMillis(150)); // beyond the 100 ms guard budget (margin 200 ms / 2)
            }
        });
        assertTrue(slow.transitionAsOwner(token, OrderStatus.ROUTED, OrderStatus.PREPARING, null), "the second attempt carries a fresh stamp");
        assertEquals(2, beforeCommits.get());
        assertEquals(1, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'PREPARING'", id), "the aborted attempt left nothing");
        assertEquals("PREPARING", h.t.text("SELECT status FROM orders WHERE id = ?", id));
    }

    @Test
    void aStampThatStaysStaleIsReportedAndNothingIsCommitted() throws Exception {
        long id = routed("LS-9");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        OrderService alwaysSlow = serviceWith(phase -> {
            if (phase == LeaseStampHook.Phase.BEFORE_COMMIT) {
                h.clock.advance(Duration.ofMillis(150));
            }
        });
        assertThrows(LeaseStampStaleException.class, () -> alwaysSlow.transitionAsOwner(token, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
        assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id));
    }

    @Test
    void aRenewalDelayedPastExpiryFailsInsteadOfReviving() throws Exception {
        long id = routed("LS-10");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        // the clock jumps beyond the lease expiry right after the stamp was taken but before the statement runs
        OrderService late = serviceWith(phase -> {
            if (phase == LeaseStampHook.Phase.AFTER_STAMP) {
                h.clock.advance(Duration.ofSeconds(40));
            }
        });
        assertThrows(LeaseStampStaleException.class, () -> late.renewLease(token), "the guard sees the statement ran too long after its stamp");
        assertFalse(h.service.renewLease(token), "and a fresh attempt finds the lease expired: no revival");
    }
}
