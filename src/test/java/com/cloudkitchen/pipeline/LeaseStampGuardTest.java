package com.cloudkitchen.pipeline;

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
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.OrderValidator;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.RecordingOrderEventLog;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** RW-18, rules S-1..S-7: a statement or commit that slips past the guard budget G = margin / 2 is rolled back whole. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaseStampGuardTest {

    private static final String ROW = "SELECT CONCAT_WS('|', status, claim_epoch, IFNULL(claimed_by, '-'), IFNULL(lease_expires_at, '-'), stock_deducted)"
            + " FROM orders WHERE id = ?";

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

    /** A service without stamp retries (so a stale stamp surfaces) whose stall hook runs {@code stall} per phase. */
    private OrderService noRetryService(Consumer<LeaseStampHook.Phase> stall) throws Exception {
        LeaseStampHook hook = stall::accept;
        Database database = new Database(h.db.pool(), h.clock, 3, 1, hook);
        JdbcOrderDao dao = new JdbcOrderDao(h.clock, DbTestSupport.MARGIN, hook);
        OrderService.Settings base = OrderService.Settings.from(h.db.config());
        OrderService.Settings settings = new OrderService.Settings(base.leaseTtl(), base.compensationRetryMax(), base.compensationBackoff(),
                base.compensationBackoffMax(), base.compensationAlertAttempts(), 0);
        OrderService service = new OrderService(database, dao, h.t.stock, h.t.history, h.t.menu, new OrderValidator(), h.routing,
                new FakeDispatchers.Accepting(), new InFlightRegistry(), new MetricsRegistry(), new RecordingOrderEventLog(), h.clock,
                SubmissionHook.NO_OP, settings);
        service.setAccepting(true);
        return service;
    }

    private long routed(String ref) throws Exception {
        return h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1)).orderId();
    }

    @Test
    void aStallWithinTheBudgetCommitsAndOneBeyondItRollsEverythingBack() throws Exception {
        long id = routed("SG-1");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        OrderService within = noRetryService(phase -> {
            if (phase == LeaseStampHook.Phase.BEFORE_COMMIT) {
                h.clock.advance(Duration.ofMillis(100)); // exactly G
            }
        });
        assertTrue(within.transitionAsOwner(token, OrderStatus.ROUTED, OrderStatus.PREPARING, null), "stall == budget is allowed");

        long id2 = routed("SG-2");
        FencingToken token2 = h.service.acquireLease(id2, "BIRYANI#1").orElseThrow();
        String before = h.t.text(ROW, id2);
        long history = h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id2);
        OrderService beyond = noRetryService(phase -> {
            if (phase == LeaseStampHook.Phase.BEFORE_COMMIT) {
                h.clock.advance(Duration.ofMillis(101));
            }
        });
        assertThrows(LeaseStampStaleException.class, () -> beyond.transitionAsOwner(token2, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
        assertEquals(before, h.t.text(ROW, id2), "status, epoch, owner, lease and stock flag are all unchanged");
        assertEquals(history, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id2), "no history row survived");
    }

    @Test
    void aStallBetweenTheStampAndTheStatementIsCaughtForRenewalsToo() throws Exception {
        long id = routed("SG-3");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        String before = h.t.text(ROW, id);
        OrderService slow = noRetryService(phase -> {
            if (phase == LeaseStampHook.Phase.AFTER_STAMP) {
                h.clock.advance(Duration.ofMillis(150));
            }
        });
        assertThrows(LeaseStampStaleException.class, () -> slow.renewLease(token));
        assertEquals(before, h.t.text(ROW, id));
    }

    @Test
    void aStallLongEnoughToExpireTheLeaseIsNeverTurnedIntoARevival() throws Exception {
        long id = routed("SG-4");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        OrderService slow = noRetryService(phase -> {
            if (phase == LeaseStampHook.Phase.AFTER_STAMP) {
                h.clock.advance(Duration.ofSeconds(40));
            }
        });
        assertThrows(LeaseStampStaleException.class, () -> slow.transitionAsOwner(token, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
        assertFalse(h.service.transitionAsOwner(token, OrderStatus.ROUTED, OrderStatus.PREPARING, null), "the fresh stamp finds the lease expired");
        assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
    }
}
