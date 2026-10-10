package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedCrash;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** RW-16 and the enqueue windows of SDD 7.8.6: reconcile re-offers exactly the lost ids and nothing else. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueReconcileTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static void pauseAndWait(PipelineHarness p) {
        p.orch.pause("BIRYANI");
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState("BIRYANI") == WorkerState.PAUSED));
    }

    @Test
    void onlyOrdinaryUnleasedOrdersAbsentFromTheQueueAreReofferedAndNothingIsWritten() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            pauseAndWait(p); // queued ids stay queued, so the physical queue can be inspected
            // t0: a lease that will have expired, an old VALIDATED order, and one awaiting compensation
            long expiredLease = p.persistedAt("QR-EXPIRED-LEASE", OrderStatus.ROUTED);
            h.service.acquireLease(expiredLease, "OTHER#1").orElseThrow();
            long oldValidated = p.persistedAt("QR-OLD-VALIDATED", OrderStatus.VALIDATED);
            long pending = p.persistedAt("QR-PENDING", OrderStatus.VALIDATED);
            assertTrue(h.t.ok(c -> h.t.orders.markCompensationPending(c, pending, "QUEUE_FULL", h.clock.instant().plusSeconds(600))));
            p.advance(Duration.ofSeconds(31)); // t0 + 31 s: the lease is free, the dispatch window (30 s) has passed for the t0 orders
            long routedAbsent = p.persistedAt("QR-ROUTED", OrderStatus.ROUTED);
            long preparingAbsent = p.persistedAt("QR-PREPARING", OrderStatus.PREPARING);
            long routedQueued = p.persistedAt("QR-QUEUED", OrderStatus.ROUTED);
            p.orch.enqueueRecovered(routedQueued, "BIRYANI");
            long freshValidated = p.persistedAt("QR-FRESH-VALIDATED", OrderStatus.VALIDATED);
            long delivered = p.persistedAt("QR-DONE", OrderStatus.DELIVERED);
            long validLease = p.persistedAt("QR-VALID-LEASE", OrderStatus.PACKED);
            h.service.acquireLease(validLease, "OTHER#2").orElseThrow();
            String leaseBefore = h.t.text("SELECT CONCAT_WS('|', claim_epoch, claimed_by, lease_expires_at) FROM orders WHERE id = ?", validLease);
            h.orderFaults.resetCounters();

            p.orch.repairAll();

            Set<Long> queued = p.orch.runtime("BIRYANI").physicalSnapshot();
            assertEquals(Set.of(expiredLease, oldValidated, routedAbsent, preparingAbsent, routedQueued), queued);
            assertEquals(5, p.orch.runtime("BIRYANI").size(), "the id that was already queued was not duplicated");
            assertTrue(!queued.contains(pending) && !queued.contains(delivered) && !queued.contains(validLease) && !queued.contains(freshValidated));
            assertEquals(leaseBefore, h.t.text("SELECT CONCAT_WS('|', claim_epoch, claimed_by, lease_expires_at) FROM orders WHERE id = ?", validLease),
                    "a valid lease is never touched");
            assertEquals(Set.of("findReconcilable"), new HashSet<>(h.orderFaults.sequence()), "reconcile only reads (R1)");
            assertTrue(h.events.all().stream().anyMatch(e -> e.type() == EventType.QUEUE_RECONCILED && e.detail().endsWith("count=4")),
                    "four ids were offered");
        } finally {
            p.orch.resume("BIRYANI");
            p.stop();
        }
    }

    @Test
    void aVeryFreshValidatedOrderIsLeftToItsSubmitter() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            pauseAndWait(p);
            long fresh = p.persistedAt("QR-FRESH", OrderStatus.VALIDATED);
            p.orch.repairAll();
            assertTrue(p.orch.runtime("BIRYANI").physicalSnapshot().isEmpty(), "the submitter may still be between commit and offer");
            p.advance(Duration.ofSeconds(31));
            p.orch.repairAll();
            assertEquals(Set.of(fresh), p.orch.runtime("BIRYANI").physicalSnapshot());
        } finally {
            p.orch.resume("BIRYANI");
            p.stop();
        }
    }

    @Test
    void aSubmitterThatDiesAfterCommitBeforeTheOfferLeavesAnOrderThatReconcileDelivers() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            h.hook.set(stage -> {
                if (stage == com.cloudkitchen.service.SubmissionHook.Stage.AFTER_TX1) {
                    throw new SimulatedCrash("after TX1");
                }
            });
            assertThrows(SimulatedCrash.class, () -> p.submit("QR-W1"));
            h.hook.set(com.cloudkitchen.service.SubmissionHook.NO_OP);
            long id = p.lastValidatedId();
            assertEquals("VALIDATED", p.status(id));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "TX1 committed: stock is held, once");
            assertEquals(0, p.orch.runtime("BIRYANI").size(), "the id was never queued");

            p.orch.repairAll();
            assertEquals(0, p.orch.runtime("BIRYANI").size(), "inside the dispatch window the submitter's window is respected");

            p.advance(Duration.ofSeconds(31));
            p.orch.repairAll();
            p.awaitDelivered(id);
            assertEquals("worker:BIRYANI#1", h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
            assertEquals(1, p.count("SELECT COUNT(*) FROM orders WHERE external_ref = 'QR-W1'"));
        } finally {
            p.stop();
        }
    }

    @Test
    void aSubmitterThatDiesAfterTheOfferNeverCompensatesAndTheOrderIsDelivered() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            h.hook.set(stage -> {
                if (stage == com.cloudkitchen.service.SubmissionHook.Stage.AFTER_OFFER) {
                    throw new SimulatedCrash("after offer");
                }
            });
            assertThrows(SimulatedCrash.class, () -> p.submit("QR-W2"));
            h.hook.set(com.cloudkitchen.service.SubmissionHook.NO_OP);
            long id = p.lastValidatedId();
            p.awaitDelivered(id);
            assertEquals(0, h.metricsDelta().failed(), "RR-1: nothing compensates a queued order");
            assertEquals(1, p.count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void aQueueThatLostItsIdsInTheMiddleOfARoutedOrderIsRepairedFromTheDatabase() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            pauseAndWait(p);
            long id = p.accepted("QR-W3"); // enqueued and ROUTED in the database
            assertEquals("ROUTED", p.status(id));
            p.orch.runtime("BIRYANI").queue().clear(); // what a restart does to the in-memory queue
            p.orch.repairAll();
            assertEquals(Set.of(id), p.orch.runtime("BIRYANI").physicalSnapshot(), "ROUTED needs no waiting");
            p.orch.resume("BIRYANI");
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
        } finally {
            p.stop();
        }
    }
}
