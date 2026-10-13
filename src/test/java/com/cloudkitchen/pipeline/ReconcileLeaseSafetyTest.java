package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** RW-13, R1..R4, RI-2: reconcile never writes lease columns, never steals a lease, and its duplicates are harmless. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconcileLeaseSafetyTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @RepeatedTest(20)
    void aDuplicateOfferedForAnIdAHealthyWorkerJustPolledIsSkippedAndTheOrderIsDeliveredOnce() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_POLL, null, null);
        try {
            long id = p.accepted("RLS-1");
            held.awaitBlocked(); // W polled the id and has not leased it: the id is in no queue and the lease is free
            h.orderFaults.resetCounters();

            p.orch.repairAll(); // reconcile sees "ROUTED, free lease, absent": it offers a duplicate

            assertEquals(Set.of("findReconcilable"), Set.copyOf(h.orderFaults.sequence()), "reconcile wrote nothing (R1)");
            assertEquals(Set.of(id), p.orch.runtime("BIRYANI").physicalSnapshot(), "the duplicate sits in the queue");
            held.release.countDown();

            p.awaitDelivered(id);
            assertTrue(h.events.awaitUntil(Duration.ofSeconds(20), () -> p.orch.runtime("BIRYANI").size() == 0
                    && h.events.countFor(EventType.ORDER_SKIPPED_TERMINAL, id) + h.events.countFor(EventType.ORDER_SKIPPED_LEASED, id) >= 1),
                    "the duplicate was polled and skipped");
            assertEquals(1, h.metricsDelta().processed(), "processed once");
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "the duplicate never took a second lease");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            held.release.countDown();
            p.stop();
        }
    }

    @RepeatedTest(20)
    void aValidLeaseHeldByALiveWorkerIsNeverStolenOrDuplicated() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.AFTER_LEASE, null, null);
        try {
            long id = p.accepted("RLS-2");
            held.awaitBlocked(); // the worker holds a valid lease
            String lease = h.t.text("SELECT CONCAT_WS('|', claim_epoch, claimed_by, lease_expires_at) FROM orders WHERE id = ?", id);
            h.orderFaults.resetCounters();

            p.orch.repairAll();

            assertEquals(lease, h.t.text("SELECT CONCAT_WS('|', claim_epoch, claimed_by, lease_expires_at) FROM orders WHERE id = ?", id));
            assertEquals(List.of(), List.copyOf(p.orch.runtime("BIRYANI").physicalSnapshot()), "nothing offered for a leased order");
            assertEquals(Set.of("findReconcilable"), Set.copyOf(h.orderFaults.sequence()));
            held.release.countDown();
            p.awaitDelivered(id);
            p.assertEachEdgeOnce(id);
        } finally {
            held.release.countDown();
            p.stop();
        }
    }

    @RepeatedTest(20)
    void repairingWhileOrdersFlowThroughTheWorkersNeverDuplicatesWork() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            List<Long> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add(p.accepted("RLS-3-" + i));
                p.orch.repairAll(); // interleaves reconcile passes with the workers' acquire/step transactions
            }
            for (long id : ids) {
                p.awaitDelivered(id);
                p.assertEachEdgeOnce(id);
                assertEquals(OrderStatus.DELIVERED.name(), p.status(id));
            }
            assertEquals(10, h.metricsDelta().processed(), "each order was processed exactly once");
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }
}
