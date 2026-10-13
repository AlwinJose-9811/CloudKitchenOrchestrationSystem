package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.10.11, OP-1..OP-8: the only manual operation on a pending order, and the races around it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationOperatorTest {

    private static final SessionUser ADMIN = new SessionUser(1, "alice", Role.ADMIN, 1);
    private static final SessionUser COOK = new SessionUser(2, "cook", Role.KITCHEN_STAFF, 1);

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
    void theOperatorRetryIgnoresTheBackoffIsAuditedAndRestoresOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.pendingOrder("CO-1"); // due only after 30 s of fake time, and the clock does not move
            assertFalse(h.service.findDuePendingCompensationIds(10).contains(id));
            assertTrue(h.service.retryCompensation(id, ADMIN), "OP-4: the manual retry does not wait for the backoff");
            assertEquals("FAILED", p.status(id));
            assertEquals("admin:alice", h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            assertEquals("COMPENSATION_MANUAL", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            assertEquals(1, h.events.countFor(EventType.COMPENSATION_RETRY_REQUESTED, id));
            assertEquals(1, h.events.countFor(EventType.COMPENSATION_COMPLETED, id));
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            assertFalse(h.service.retryCompensation(id, ADMIN), "a second click is refused without effect");
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void kitchenStaffAndAnonymousCallersAreRefusedAndCancelCannotBypassThePendingOrder() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.pendingOrder("CO-2");
            CloudKitchenException forbidden = assertThrows(CloudKitchenException.class, () -> h.service.retryCompensation(id, COOK));
            assertEquals("FORBIDDEN", forbidden.getCode());
            assertThrows(CloudKitchenException.class, () -> h.service.retryCompensation(id, null));
            assertEquals(CancelOutcome.AWAITING_COMPENSATION, h.service.cancelOrderDetailed(id, "cook"), "OP-7: cancel refuses a pending order");
            assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertEquals("VALIDATED", p.status(id));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
        } finally {
            p.stop();
        }
    }

    @Test
    void aFailedOperatorRetryChangesNothingExceptTheAttemptBookkeeping() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.pendingOrder("CO-3");
            h.stockFaults.failAlways("restore", PipelineHarness.outage());
            assertThrows(CloudKitchenException.class, () -> h.service.retryCompensation(id, ADMIN));
            assertEquals("VALIDATED", p.status(id));
            assertEquals("2", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            p.assertStockLedger();
        } finally {
            h.stockFaults.clearFaults();
            p.stop();
        }
    }

    @RepeatedTest(20)
    void operatorSweeperAndStaffCancelRacingResolveTheOrderExactlyOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        ExecutorService executor = Executors.newFixedThreadPool(3, r -> new Thread(r, "test-op-race"));
        try {
            long id = p.pendingOrder("CO-RACE");
            p.advance(Duration.ofSeconds(31)); // due for the sweeper as well
            OrphanSweeper sweeper = new OrphanSweeper(h.service, p.orch, new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE),
                    OrphanSweeperTest.SETTINGS, h.clock, h.events);
            CyclicBarrier barrier = new CyclicBarrier(3);
            Future<Boolean> operator = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return h.service.retryCompensation(id, ADMIN);
            });
            Future<?> sweep = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                sweeper.completeDueCompensations();
                return null;
            });
            Future<CancelOutcome> cancel = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return h.service.cancelOrderDetailed(id, "cook");
            });
            operator.get(30, TimeUnit.SECONDS);
            sweep.get(30, TimeUnit.SECONDS);
            CancelOutcome cancelOutcome = cancel.get(30, TimeUnit.SECONDS);
            assertEquals("FAILED", p.status(id));
            assertTrue(cancelOutcome == CancelOutcome.AWAITING_COMPENSATION || cancelOutcome == CancelOutcome.NOT_CANCELLABLE, "" + cancelOutcome);
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id), "one terminal edge");
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id), "stock restored exactly once");
            assertEquals(1, h.metricsDelta().failed());
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }
}
