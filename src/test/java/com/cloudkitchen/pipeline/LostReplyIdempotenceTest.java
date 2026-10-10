package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.testsupport.FailingCommitDataSource;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * RW-12, FI-3, FI-6: the commit happened but the worker never heard back (the data source commits for real and then
 * reports a connection failure). Retrying must be harmless: the same owner re-acquires, a retried step finds the order
 * already advanced and continues, a retried failure finds it terminal and stands down. The only effect of a lost reply
 * is that the post-commit event and metric of that one step are not emitted (they follow the reply).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LostReplyIdempotenceTest {

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
    void aLostAcquireReplyLeavesALeaseThatTheSameOwnerCanTakeAgainButNobodyElse() throws Exception {
        h.reset();
        FailingCommitDataSource lossy = new FailingCommitDataSource(h.db.pool());
        OrderService service = PipelineHarness.serviceOver(h, lossy);
        long id = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "LRI-0", "BIRYANI", "Chicken Biryani", 1)).orderId();
        lossy.loseNextReplies(1);
        assertThrows(PersistenceException.class, () -> service.acquireLease(id, "BIRYANI#1"));
        assertEquals("BIRYANI#1", h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "the lease was committed");
        assertTrue(service.acquireLease(id, "BIRYANI#2").isEmpty(), "another owner is kept out until it expires");
        assertEquals(2, service.acquireLease(id, "BIRYANI#1").orElseThrow().epoch(), "the same owner string may retry (FI-6)");
    }

    @Test
    void aWorkerWhoseAcquireReplyWasLostRetriesAndIsNotLockedOutByItsOwnLease() throws Exception {
        FailingCommitDataSource lossy = new FailingCommitDataSource(h.db.pool());
        h.reset();
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast(), h.clock, PipelineHarness.serviceOver(h, lossy));
        AtomicBoolean armed = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && armed.compareAndSet(false, true)) {
                lossy.loseNextReplies(1); // the next commit of the worker's service is its lease acquisition
            }
        });
        try {
            long id = p.accepted("LRI-1");
            p.awaitDelivered(id);
            assertEquals(0, h.events.countFor(EventType.ORDER_SKIPPED_LEASED, id), "the worker did not treat its own lease as foreign");
            assertEquals(2, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "committed once, then taken again by the same owner");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void aLostStepReplyIsRecognisedOnTheRetryAndTheWorkerContinuesFromTheCommittedStatus() throws Exception {
        FailingCommitDataSource lossy = new FailingCommitDataSource(h.db.pool());
        h.reset();
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast(), h.clock, PipelineHarness.serviceOver(h, lossy));
        AtomicBoolean armed = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_LEASE && armed.compareAndSet(false, true)) {
                lossy.loseNextReplies(1); // the next commit is the step ROUTED -> PREPARING
            }
        });
        try {
            long id = p.persistedAt("LRI-2", OrderStatus.ROUTED);
            p.orch.enqueueRecovered(id, "BIRYANI");
            p.awaitDelivered(id);
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "no re-acquisition was needed (L-5, continue)");
            assertEquals(0, h.events.countFor(EventType.LEASE_REACQUIRED, id));
            assertEquals(0, h.events.countFor(EventType.LEASE_LOST, id));
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'PREPARING'", id), "no second history row");
            p.assertEachEdgeOnce(id);
        } finally {
            p.stop();
        }
    }

    @Test
    void aLostFailureReplyIsNotRetriedIntoASecondFailureOrASecondRestore() throws Exception {
        FailingCommitDataSource lossy = new FailingCommitDataSource(h.db.pool());
        h.reset();
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast(), h.clock, PipelineHarness.serviceOver(h, lossy));
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.BEFORE_STEP && step == OrderStatus.ROUTED && fired.compareAndSet(false, true)) {
                lossy.loseNextReplies(1); // the next commit is failAsOwner
                throw new IllegalStateException("permanent processing error");
            }
        });
        try {
            long id = p.accepted("LRI-3");
            // a lost reply emits no event, so this one wait polls the committed status and then the worker's state
            assertTrue(PipelineHarness.awaitCondition(PipelineHarness.WAIT, () -> "FAILED".equals(p.status(id))), "the failure committed");
            assertTrue(PipelineHarness.awaitCondition(PipelineHarness.WAIT, () -> fired.get()
                    && p.workerState("BIRYANI") == com.cloudkitchen.domain.enums.WorkerState.IDLE), "the retried failure ended the worker's handling");
            assertEquals("FAILED", p.status(id));
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "restored by the committed transaction");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger(); // a second restore would make the stock exceed the starting value
        } finally {
            p.stop();
        }
    }
}
