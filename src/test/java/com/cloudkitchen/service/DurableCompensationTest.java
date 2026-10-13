package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.dto.SubmissionResult.Outcome;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.service.OrderService.CompensationOutcome;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.10: an order that could not be queued is either failed with its stock restored, or durably marked PENDING. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DurableCompensationTest {

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

    private static SQLTransientConnectionException down() {
        return new SQLTransientConnectionException("database unavailable");
    }

    /** Submits with a full queue while the restore step is broken, so the order ends up VALIDATED and PENDING. */
    private long pendingOrder(String ref) throws Exception {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        h.orderFaults.failAlways("claimStockRestore", down());
        SubmissionResult r = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1));
        assertEquals(Outcome.QUEUE_FULL, r.outcome());
        assertFalse(r.compensated(), "the marker was committed instead of the compensation");
        h.orderFaults.clearFaults();
        return r.orderId();
    }

    @Test
    void whenCompensationCannotCommitTheDurableMarkerIsCommittedAndStockStaysDeducted() throws Exception {
        long id = pendingOrder("DC-1");
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
        assertEquals("QUEUE_FULL", h.t.text("SELECT compensation_reason FROM orders WHERE id = ?", id));
        assertEquals("1", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", id));
        assertEquals(48, h.t.stockOf("Chicken Biryani"), "DC-3: marking never touches stock");
        assertEquals(1, h.events.count(EventType.COMPENSATION_PENDING));
        assertEquals(0, h.metricsDelta().failed(), "not failed yet");
        assertEquals(4, h.orderFaults.calls("claimStockRestore"), "one attempt plus compensation.retry.max (3) more");
    }

    @Test
    void whenEvenTheMarkerCannotBeWrittenTheResultIsIndeterminateAndTheOrderStaysOrdinary() throws Exception {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        h.orderFaults.failAlways("claimStockRestore", down());
        h.orderFaults.failAlways("markCompensationPending", down());
        SubmissionResult r = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "DC-2", "BIRYANI", "Raita", 1));
        assertEquals(Outcome.INDETERMINATE, r.outcome());
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", r.orderId()));
        assertEquals(null, h.t.text("SELECT compensation_state FROM orders WHERE id = ?", r.orderId()), "nothing was promised");
        assertEquals(49, h.t.stockOf("Raita"));
    }

    @Test
    void aPendingOrderCanNeitherBeLeasedNorRoutedNorCancelled() throws Exception {
        long id = pendingOrder("DC-3");
        assertTrue(h.service.acquireLease(id, "BIRYANI#1").isEmpty(), "guard 1");
        assertFalse(h.service.transition(id, OrderStatus.VALIDATED, OrderStatus.ROUTED, "SYSTEM", null), "guard 2");
        assertEquals(CancelOutcome.AWAITING_COMPENSATION, h.service.cancelOrderDetailed(id, "staff"));
        assertFalse(h.service.cancelOrder(id, "staff"));
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
    }

    @Test
    void completingAPendingCompensationRestoresStockOnceAndResolvesTheMarker() throws Exception {
        long id = pendingOrder("DC-4");
        assertTrue(h.service.completePendingCompensation(id));
        assertEquals("FAILED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals("RESOLVED", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id), "resolved in the same UPDATE");
        assertEquals("0", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", id));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(50, h.t.stockOf("Raita"));
        assertEquals("QUEUE_FULL", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id), "the stored reason is the history note");
        assertEquals(1, h.events.count(EventType.COMPENSATION_COMPLETED));
        assertEquals(1, h.metricsDelta().failed());
        assertFalse(h.service.completePendingCompensation(id), "a repeat finds nothing to do");
        assertEquals(50, h.t.stockOf("Chicken Biryani"), "restored exactly once");
        assertEquals(1, h.metricsDelta().failed());
    }

    @Test
    void aFailedAttemptRecordsExponentialBackoffCappedAtTheMaximum() throws Exception {
        long id = pendingOrder("DC-5");
        h.orderFaults.failAlways("transition", down());
        long[] expectedSeconds = {60, 120, 240, 480, 600, 600}; // attempts so far: 1,2,3,4,5,6 -> 30 * 2^n capped at 600
        for (int i = 0; i < expectedSeconds.length; i++) {
            assertFalse(h.service.completePendingCompensation(id));
            assertEquals(i + 2, h.t.number("SELECT compensation_attempts FROM orders WHERE id = ?", id));
            long next = h.t.number("SELECT TIMESTAMPDIFF(SECOND, ?, compensation_next_at) FROM orders WHERE id = ?",
                    java.time.LocalDateTime.ofInstant(h.clock.instant(), java.time.ZoneOffset.UTC), id);
            assertEquals(expectedSeconds[i], next, "attempt " + (i + 2));
        }
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", id), "never failed without restoring stock (DC-6)");
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
        h.orderFaults.clearFaults();
        assertTrue(h.service.completePendingCompensation(id), "the retry still works after many failures");
    }

    @Test
    void theAlertIsRaisedFromTheConfiguredNumberOfAttempts() throws Exception {
        long id = pendingOrder("DC-6");
        h.orderFaults.failAlways("transition", down());
        for (int i = 0; i < 9; i++) {
            h.service.completePendingCompensation(id);
        }
        assertEquals(1, h.events.count(EventType.COMPENSATION_STUCK), "attempts: 1 (marker) + 9 failures = 10 = compensation.alert.attempts");
    }

    // ---- the operator operation (OP-1..OP-8) ----

    private static SessionUser user(Role role, String name) {
        return new SessionUser(7, name, role, 0);
    }

    @Test
    void onlyAnAdministratorMayRetryAndTheRetryIsAuditedWithTheAdminActor() throws Exception {
        long id = pendingOrder("OP-1");
        CloudKitchenException forbidden = assertThrows(CloudKitchenException.class, () -> h.service.retryCompensation(id, user(Role.KITCHEN_STAFF, "cook")));
        assertEquals("FORBIDDEN", forbidden.getCode());
        assertThrows(CloudKitchenException.class, () -> h.service.retryCompensation(id, null));
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", id));

        assertTrue(h.service.retryCompensation(id, user(Role.ADMIN, "alice")));
        assertEquals("FAILED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals("admin:alice", h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
        assertEquals("COMPENSATION_MANUAL", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(1, h.events.count(EventType.COMPENSATION_RETRY_REQUESTED));
        assertEquals(1, h.events.count(EventType.COMPENSATION_COMPLETED));
        assertFalse(h.service.retryCompensation(id, user(Role.ADMIN, "alice")), "double click: already resolved, nothing happens");
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(1, h.metricsDelta().failed());
    }

    @Test
    void aFailedOperatorRetryRollsBackRecordsTheAttemptAndReportsTheError() throws Exception {
        long id = pendingOrder("OP-2");
        h.stockFaults.failAfter("restore", 1, down());
        assertThrows(CloudKitchenException.class, () -> h.service.retryCompensation(id, user(Role.ADMIN, "alice")));
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
        assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
        assertEquals("2", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));
        h.stockFaults.clearFaults();
        assertTrue(h.service.retryCompensation(id, user(Role.ADMIN, "alice")));
    }

    @Test
    void retryingAnOrderThatIsNotPendingIsRefusedWithoutSideEffects() throws Exception {
        long routed = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "OP-3", "BIRYANI", "Raita", 1)).orderId();
        assertFalse(h.service.retryCompensation(routed, user(Role.ADMIN, "alice")));
        assertFalse(h.service.retryCompensation(424242L, user(Role.ADMIN, "alice")));
        assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", routed));
    }

    @Test
    void compensateReportsAlreadyHandledWhenSomeoneElseGotThereFirst() throws Exception {
        long id = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "CH-1", "BIRYANI", "Raita", 1)).orderId();
        assertTrue(h.service.cancelOrder(id, "staff")); // staff cancelled the ROUTED order meanwhile
        assertEquals(CompensationOutcome.ALREADY_HANDLED, h.service.compensate(id, "QUEUE_FULL"));
        assertEquals(50, h.t.stockOf("Raita"), "no second restore");
        assertEquals(0, h.metricsDelta().failed(), "I-5: the loser counts nothing");
    }

    @Test
    void compensationIsIdempotentUnderRepeatedCalls() throws Exception {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        SubmissionResult r = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "CH-2", "BIRYANI", "Raita", 1));
        assertTrue(r.compensated());
        assertEquals(CompensationOutcome.ALREADY_HANDLED, h.service.compensate(r.orderId(), "QUEUE_FULL"));
        assertEquals(CompensationOutcome.ALREADY_HANDLED, h.service.compensate(r.orderId(), "QUEUE_FULL"));
        assertEquals(50, h.t.stockOf("Raita"));
        assertEquals(1, h.metricsDelta().failed());
        assertEquals(Duration.ofSeconds(30), OrderService.Settings.from(h.db.config()).compensationBackoff());
    }
}
