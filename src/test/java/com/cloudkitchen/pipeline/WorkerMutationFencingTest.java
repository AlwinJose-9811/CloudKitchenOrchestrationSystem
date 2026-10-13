package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * WM-1..WM-6 (SDD 7.11.12): a worker holding a stale token changes nothing and executes exactly one statement
 * (the fenced first statement, F-1/F-2): no history, no stock flag flip, no inventory write, no metric, no event.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerMutationFencingTest {

    /** How the token of worker A became stale. */
    enum Staleness { SUPERSEDED, EXPIRED, CLEARED_BY_MONITOR }

    private static final String ROW = "SELECT CONCAT_WS('|', status, claim_epoch, IFNULL(claimed_by, '-'), IFNULL(lease_expires_at, '-'),"
            + " stock_deducted) FROM orders WHERE id = ?";
    private static final String STOCK = "SELECT GROUP_CONCAT(stock_qty ORDER BY item_id) FROM inventory";

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
        return h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1)).orderId();
    }

    private void makeStale(Staleness how, long id) throws Exception {
        switch (how) {
            case SUPERSEDED -> {
                h.clock.advance(Duration.ofSeconds(31));
                h.service.acquireLease(id, "BIRYANI#2").orElseThrow();
            }
            case EXPIRED -> h.clock.advance(Duration.ofSeconds(31));
            case CLEARED_BY_MONITOR -> h.service.reclaimLeasesOf("BIRYANI#1");
        }
    }

    private void quiet() {
        h.orderFaults.resetCounters();
        h.historyFaults.resetCounters();
        h.stockFaults.resetCounters();
        h.events.clear();
    }

    private void assertNothingChanged(long id, String row, String stock, long history) throws Exception {
        assertEquals(row, h.t.text(ROW, id));
        assertEquals(stock, h.t.text(STOCK));
        assertEquals(history, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id));
        assertEquals(List.of(), h.historyFaults.sequence(), "F-2: no history statement ran");
        assertEquals(List.of(), h.stockFaults.sequence(), "F-2/F-5: no inventory statement ran");
        assertEquals(0, h.events.all().size(), "I-5: no event");
    }

    @ParameterizedTest
    @EnumSource(Staleness.class)
    void aStaleStepRunsOneStatementAndChangesNothing(Staleness how) throws Exception {
        long id = routed("WMF-STEP-" + how);
        FencingToken a = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        makeStale(how, id);
        String row = h.t.text(ROW, id);
        String stock = h.t.text(STOCK);
        long history = h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id);
        quiet();
        assertFalse(h.service.transitionAsOwner(a, OrderStatus.ROUTED, OrderStatus.PREPARING, null));
        assertEquals(List.of("transition"), h.orderFaults.sequence(), "exactly the fenced first statement");
        assertNothingChanged(id, row, stock, history);
    }

    @ParameterizedTest
    @EnumSource(Staleness.class)
    void aStaleFailureDoesNotFailTheOrderOrRestoreItsStock(Staleness how) throws Exception {
        long id = routed("WMF-FAIL-" + how);
        FencingToken a = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        makeStale(how, id);
        String row = h.t.text(ROW, id);
        String stock = h.t.text(STOCK);
        long history = h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id);
        quiet();
        assertFalse(h.service.failAsOwner(a, OrderStatus.ROUTED, "PROCESSING_ERROR"));
        assertEquals(List.of("transition"), h.orderFaults.sequence(), "F-2: claimStockRestore never ran");
        assertNothingChanged(id, row, stock, history);
        assertEquals(0, h.metricsDelta().failed());
    }

    @ParameterizedTest
    @EnumSource(Staleness.class)
    void aStaleSelfRouteOfAValidatedOrderChangesNothing(Staleness how) throws Exception {
        h.dispatcher.use(new com.cloudkitchen.testsupport.FakeDispatchers.AlwaysFull(com.cloudkitchen.exception.QueueFullException.Reason.QUEUE_FULL));
        // a VALIDATED order that was never routed: persist it and give it a stock flag like TX1 would
        long id = h.t.persistAt(h.t.biryaniOrder("WMF-ROUTE-" + how), OrderStatus.VALIDATED);
        FencingToken a = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        makeStale(how, id);
        String row = h.t.text(ROW, id);
        String stock = h.t.text(STOCK);
        long history = h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id);
        quiet();
        assertFalse(h.service.transitionAsOwner(a, OrderStatus.VALIDATED, OrderStatus.ROUTED, "ROUTED_BY_WORKER"));
        assertEquals(List.of("transition"), h.orderFaults.sequence());
        assertNothingChanged(id, row, stock, history);
    }

    @ParameterizedTest
    @EnumSource(Staleness.class)
    void aStaleRenewalOrReleaseWritesNothing(Staleness how) throws Exception {
        long id = routed("WMF-LEASE-" + how);
        FencingToken a = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        makeStale(how, id);
        String row = h.t.text(ROW, id);
        quiet();
        assertFalse(h.service.renewLease(a));
        assertEquals(List.of("renewLease"), h.orderFaults.sequence());
        if (how != Staleness.EXPIRED) {
            // an expired lease that nobody took may still be released by its own epoch and owner; the other two may not
            h.service.releaseLease(a);
            assertEquals(row, h.t.text(ROW, id), "WM-3: a stale owner cannot free or touch a newer or cleared lease");
        }
        assertEquals(0, h.events.all().size());
        assertEquals(row, h.t.text(ROW, id));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PREPARING", "PACKED", "OUT_FOR_DELIVERY"})
    void aStaleStepFromLaterStatusesIsRejectedTheSameWay(OrderStatus from) throws Exception {
        long id = routed("WMF-LATER-" + from);
        FencingToken a = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        // bring the order to `from` with the real owner, then let A's token go stale by supersession
        OrderStatus current = OrderStatus.ROUTED;
        for (OrderStatus next : List.of(OrderStatus.PREPARING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY)) {
            if (current == from) {
                break;
            }
            assertEquals(true, h.service.transitionAsOwner(a, current, next, null));
            current = next;
        }
        makeStale(Staleness.SUPERSEDED, id);
        OrderStatus to = from == OrderStatus.PREPARING ? OrderStatus.PACKED : from == OrderStatus.PACKED ? OrderStatus.OUT_FOR_DELIVERY : OrderStatus.DELIVERED;
        String row = h.t.text(ROW, id);
        String stock = h.t.text(STOCK);
        long history = h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id);
        quiet();
        assertFalse(h.service.transitionAsOwner(a, from, to, null));
        assertEquals(List.of("transition"), h.orderFaults.sequence());
        assertNothingChanged(id, row, stock, history);
        assertEquals(0, h.events.countFor(EventType.ORDER_DELIVERED, id));
    }
}
