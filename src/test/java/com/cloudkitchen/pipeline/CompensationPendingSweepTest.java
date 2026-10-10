package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.9.3 step 1 / 7.10.8: the sweeper completes due pending compensations with backoff and never queues them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationPendingSweepTest {

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
    void aPendingCompensationIsRetriedWhenDueWithGrowingBackoffAndCompletedOnceTheFaultIsGone() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.pendingOrder("CPS-1");
            OrphanSweeper sweeper = new OrphanSweeper(h.service, p.orch, new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE),
                    OrphanSweeperTest.SETTINGS, h.clock, h.events);
            assertEquals("1", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));

            sweeper.completeDueCompensations(); // not due yet (backoff 30 s)
            assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertEquals("1", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id), "no attempt was made");

            h.stockFaults.failAlways("restore", PipelineHarness.outage());
            p.advance(Duration.ofSeconds(31));
            sweeper.completeDueCompensations(); // due, but the restore still fails
            assertEquals("2", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));
            assertEquals("VALIDATED", p.status(id));
            assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "the failed attempt rolled back completely");
            sweeper.completeDueCompensations(); // the new backoff (60 s) has not passed
            assertEquals("2", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));

            h.stockFaults.clearFaults();
            p.advance(Duration.ofSeconds(61));
            sweeper.completeDueCompensations();
            assertEquals("FAILED", p.status(id));
            assertEquals("RESOLVED", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
            assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            assertEquals(1, h.events.countFor(EventType.COMPENSATION_COMPLETED, id));
            assertEquals(1, h.metricsDelta().failed());
            assertEquals(1, sweeper.stats().compensationsCompleted());
            sweeper.completeDueCompensations();
            assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id), "a later pass does nothing");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void aPendingOrderIsNeverLeasedQueuedOrProcessedWhileTheSweeperRetriesIt() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.pendingOrder("CPS-2");
            OrphanSweeper sweeper = new OrphanSweeper(h.service, p.orch, new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE),
                    new OrphanSweeper.Settings(true, 3600, 60, 1, 100), h.clock, h.events);
            h.stockFaults.failAlways("restore", PipelineHarness.outage());
            p.advance(Duration.ofSeconds(200)); // far beyond the orphan threshold and every backoff
            for (int i = 0; i < 3; i++) {
                sweeper.runOnce();
                p.advance(Duration.ofSeconds(700));
            }
            assertTrue(p.orch.runtime("BIRYANI").physicalSnapshot().isEmpty(), "never in a queue, also not as an 'orphan'");
            assertEquals(0, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "never leased");
            assertEquals("VALIDATED", p.status(id));
            assertEquals(0, sweeper.stats().reenqueued());
            assertFalse(Long.parseLong(h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id)) < 2);
        } finally {
            h.stockFaults.clearFaults();
            p.stop();
        }
    }
}
