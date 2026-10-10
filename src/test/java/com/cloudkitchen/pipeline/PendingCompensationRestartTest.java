package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-RES-09, SDD 7.10.6: a pending order is never processed, also after restarts, and is resolved as soon as it can be. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingCompensationRestartTest {

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
    void aPendingOrderSurvivesRestartsUnprocessedUntilItsCompensationFinallySucceeds() throws Exception {
        PipelineHarness run1 = PipelineHarness.start(h, PipelineHarness.fast());
        long id = run1.pendingOrder("PCR-1");
        run1.crash();

        // run 2: the outage is still on, so recovery cannot resolve the order and must not enqueue it either
        h.stockFaults.failAlways("restore", PipelineHarness.outage());
        PipelineHarness run2 = run1.restart(PipelineHarness.fast());
        try {
            RecoveryService recovery2 = new RecoveryService(h.service, run2.orch, h.events, RecoveryService.SnapshotStep.NONE);
            recovery2.recover();
            assertEquals(1, recovery2.stats().compensationsStillPending());
            assertEquals(0, recovery2.stats().enqueued());
            assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertEquals("2", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id), "the failed round was recorded");
            assertTrue(run2.orch.runtime("BIRYANI").queue().isEmpty());
            // the monitor's reconcile pass and the sweeper's orphan step, far beyond every threshold, must not pick it up either
            run2.advance(Duration.ofSeconds(5000));
            run2.orch.monitor().runOnce();
            new OrphanSweeper(h.service, run2.orch, recovery2, new OrphanSweeper.Settings(true, 3600, 60, 1, 100), h.clock, h.events).reenqueueOrphans();
            assertTrue(run2.orch.runtime("BIRYANI").queue().isEmpty());
            assertEquals(0, run2.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "never leased by any worker");
            assertEquals("VALIDATED", run2.status(id));
            run2.crash();

            // run 3: the outage is over, recovery resolves it before it enqueues anything
            h.stockFaults.clearFaults();
            PipelineHarness run3 = run2.restart(PipelineHarness.fast());
            try {
                RecoveryService recovery3 = new RecoveryService(h.service, run3.orch, h.events, RecoveryService.SnapshotStep.NONE);
                recovery3.recover();
                assertEquals(1, recovery3.stats().compensationsResolved());
                assertEquals("FAILED", run3.status(id));
                assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
                assertEquals(0, run3.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
                run3.assertEachEdgeOnce(id);
                run3.assertStockLedger();
            } finally {
                run3.stop();
            }
        } finally {
            h.stockFaults.clearFaults();
            run2.stop();
        }
    }
}
