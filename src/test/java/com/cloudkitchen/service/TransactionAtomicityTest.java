package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.dto.SubmissionResult.Outcome;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * NFR-REL-04 / SDD 13.1: a fault injected between any two steps of a workflow rolls back everything the workflow had
 * done (status, history, stock flag, stock), and repeating the operation afterwards has the documented effect.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionAtomicityTest {

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

    private static SQLTransientConnectionException boom() {
        return new SQLTransientConnectionException("injected fault");
    }

    private OrderRequest request(String ref) throws Exception {
        return h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1);
    }

    // ---- TX1 ----

    private void assertNothingPersisted(String where) throws Exception {
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"), where + ": orders");
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_items"), where + ": items");
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_status_history"), where + ": history");
        assertEquals(750, h.t.number("SELECT SUM(stock_qty) FROM inventory"), where + ": stock");
        assertEquals(0, h.metricsDelta().failed(), where);
        assertEquals(0, h.events.count(EventType.ORDER_VALIDATED), where + ": no event for a rolled-back order");
    }

    private void tx1Fault(String label, Consumer<ServiceHarness> arm) throws Exception {
        h.reset();
        arm.accept(h);
        SubmissionResult failed = h.service.submitOrder(request("AT-" + label));
        assertEquals(Outcome.REJECTED, failed.outcome(), label);
        assertEquals(SubmissionResult.TEMPORARY_FAILURE, failed.reasonCode(), label);
        assertNothingPersisted(label);
        h.orderFaults.clearFaults();
        h.stockFaults.clearFaults();
        h.historyFaults.clearFaults();
        SubmissionResult again = h.service.submitOrder(request("AT-" + label));
        assertEquals(Outcome.ACCEPTED, again.outcome(), label + ": the repeat is an ordinary first attempt");
        assertEquals(48, h.t.stockOf("Chicken Biryani"), label + ": deducted exactly once after the repeat");
        assertEquals(1, h.t.number("SELECT COUNT(*) FROM orders"), label);
    }

    @Test
    void aFaultAfterEachStepOfOrderCreationRollsBackTheWholeOrder() throws Exception {
        tx1Fault("insert", x -> x.orderFaults.failAfter("insert", 1, boom()));
        tx1Fault("items", x -> x.orderFaults.failAfter("insertItems", 1, boom()));
        tx1Fault("history1", x -> x.historyFaults.failAfter("insert", 1, boom()));
        tx1Fault("deduct1", x -> x.stockFaults.failAfter("deduct", 1, boom()));
        tx1Fault("deduct2", x -> x.stockFaults.failAfter("deduct", 2, boom()));
        tx1Fault("flag", x -> x.orderFaults.failAfter("markStockDeducted", 1, boom()));
        tx1Fault("validated", x -> x.orderFaults.failAfter("transition", 1, boom()));
        tx1Fault("history2", x -> x.historyFaults.failAfter("insert", 2, boom()));
    }

    @Test
    void aFaultBeforeEachStepRollsBackTooAndAnApplicationErrorPropagatesWithoutPartialState() throws Exception {
        tx1Fault("pre-deduct2", x -> x.stockFaults.failBefore("deduct", 2, boom()));
        tx1Fault("pre-history2", x -> x.historyFaults.failBefore("insert", 2, boom()));
        h.reset();
        h.stockFaults.failAfter("deduct", 2, new IllegalStateException("bug in a step"));
        assertThrows(IllegalStateException.class, () -> h.service.submitOrder(request("AT-bug")));
        assertNothingPersisted("runtime error");
        assertEquals(0, h.inFlight.size(), "the in-flight guard is released even when a step throws");
    }

    // ---- TX3: status change + history + guarded restore ----

    private long routedOrder(String ref) throws Exception {
        long id = h.service.submitOrder(request(ref)).orderId();
        h.orderFaults.resetCounters();
        h.stockFaults.resetCounters();
        h.historyFaults.resetCounters();
        h.events.clear();
        return id;
    }

    private void assertUnchangedRouted(long id, String where) throws Exception {
        assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", id), where + ": status");
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id), where + ": history");
        assertEquals("1", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", id), where + ": flag");
        assertEquals(48, h.t.stockOf("Chicken Biryani"), where + ": stock");
        assertEquals(49, h.t.stockOf("Raita"), where + ": stock");
        assertEquals(0, h.events.count(EventType.ORDER_CANCELLED), where + ": no event");
    }

    private void cancelFault(String label, Consumer<ServiceHarness> arm) throws Exception {
        h.reset();
        long id = routedOrder("CF-" + label);
        arm.accept(h);
        assertThrows(PersistenceException.class, () -> h.service.cancelOrder(id, "staff"), label);
        assertUnchangedRouted(id, label);
        h.orderFaults.clearFaults();
        h.stockFaults.clearFaults();
        h.historyFaults.clearFaults();
        assertTrue(h.service.cancelOrder(id, "staff"), label + ": the retry works");
        assertEquals(50, h.t.stockOf("Chicken Biryani"), label + ": restored exactly once");
        assertEquals(50, h.t.stockOf("Raita"), label);
        assertEquals("0", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", id), label);
        assertEquals(4, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id), label);
    }

    @Test
    void aFaultBetweenAnyTwoStepsOfACancelWithRestoreRollsBackStatusHistoryFlagAndStockTogether() throws Exception {
        cancelFault("after-status", x -> x.orderFaults.failAfter("transition", 1, boom()));
        cancelFault("after-history", x -> x.historyFaults.failAfter("insert", 1, boom()));
        cancelFault("after-claim", x -> x.orderFaults.failAfter("claimStockRestore", 1, boom()));
        cancelFault("after-items", x -> x.orderFaults.failAfter("findItems", 1, boom()));
        cancelFault("after-first-restore", x -> x.stockFaults.failAfter("restore", 1, boom()));
        cancelFault("after-last-restore", x -> x.stockFaults.failAfter("restore", 2, boom()));
        cancelFault("before-claim", x -> x.orderFaults.failBefore("claimStockRestore", 1, boom()));
    }

    // ---- owner-fenced worker mutations ----

    @Test
    void aFaultInsideAnOwnerFencedFailureRollsBackExactlyLikeTheUnfencedOne() throws Exception {
        for (String step : List.of("after-fenced-update", "after-history", "after-claim", "after-last-restore")) {
            h.reset();
            long id = routedOrder("FF-" + step);
            FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
            switch (step) {
                case "after-fenced-update" -> h.orderFaults.failAfter("transition", 1, boom());
                case "after-history" -> h.historyFaults.failAfter("insert", 1, boom());
                case "after-claim" -> h.orderFaults.failAfter("claimStockRestore", 1, boom());
                default -> h.stockFaults.failAfter("restore", 2, boom());
            }
            assertThrows(CloudKitchenException.class, () -> h.service.failAsOwner(token, OrderStatus.ROUTED, "PROCESSING_ERROR"), step);
            assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", id), step);
            assertEquals(3, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id), step);
            assertEquals(48, h.t.stockOf("Chicken Biryani"), step);
            assertEquals(0, h.metricsDelta().failed(), step);
            h.orderFaults.clearFaults();
            h.stockFaults.clearFaults();
            h.historyFaults.clearFaults();
            assertTrue(h.service.failAsOwner(token, OrderStatus.ROUTED, "PROCESSING_ERROR"), step + ": retry with the same token");
            assertEquals(50, h.t.stockOf("Chicken Biryani"), step);
            assertEquals(1, h.metricsDelta().failed(), step);
        }
    }

    // ---- TX4 compensation ----

    @Test
    void aFaultInsideTheCompensationTransactionIsRetriedAndLeavesNoHalfRestoredStock() throws Exception {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        h.stockFaults.failAfter("restore", 1, boom()); // first attempt restores one line, then fails: rolled back; the second attempt succeeds
        SubmissionResult r = h.service.submitOrder(request("CP-1"));
        assertEquals(Outcome.QUEUE_FULL, r.outcome());
        assertTrue(r.compensated());
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(50, h.t.stockOf("Raita"), "every line restored exactly once, none twice");
        assertEquals(750, h.t.number("SELECT SUM(stock_qty) FROM inventory"));
        assertEquals("0", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", r.orderId()));
        assertEquals(1, h.metricsDelta().failed());
    }

    // ---- the first-statement rule (TXR-3 / F-2) ----

    @Test
    void aZeroRowFirstStatementIsFollowedByNoOtherStatement() throws Exception {
        long id = routedOrder("FS-1");
        assertTrue(h.service.cancelOrder(id, "staff"));
        h.orderFaults.resetCounters();
        h.historyFaults.resetCounters();
        h.stockFaults.resetCounters();
        assertEquals(false, h.service.transition(id, OrderStatus.ROUTED, OrderStatus.CANCELLED, "late", null), "stale expected status");
        assertEquals(List.of("transition"), h.orderFaults.sequence(), "only the conditional update ran");
        assertEquals(List.of(), h.historyFaults.sequence());
        assertEquals(List.of(), h.stockFaults.sequence());

        long id2 = routedOrder("FS-2");
        FencingToken stale = new FencingToken(id2, "BIRYANI#1", 99);
        h.orderFaults.resetCounters();
        assertEquals(false, h.service.failAsOwner(stale, OrderStatus.ROUTED, "PROCESSING_ERROR"));
        assertEquals(List.of("transition"), h.orderFaults.sequence());
        assertEquals(List.of(), h.historyFaults.sequence());
        assertEquals(List.of(), h.stockFaults.sequence());
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
    }

    @Test
    void aSuccessfulRestoringTransitionRunsTheDocumentedStatementsInOrder() throws Exception {
        long id = routedOrder("FS-3");
        assertTrue(h.service.cancelOrder(id, "staff"));
        assertEquals(List.of("findState", "transition", "claimStockRestore", "findItems"), h.orderFaults.sequence());
        assertEquals(List.of("insert"), h.historyFaults.sequence());
        assertEquals(List.of("restore", "restore"), h.stockFaults.sequence());
    }
}
