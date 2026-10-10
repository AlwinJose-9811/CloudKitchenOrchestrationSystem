package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.FakeClock;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * RW-17 / RI-3a: a worker pauses between deciding to renew and the DAO call; while it is paused the lease expires.
 * The renewal must take a fresh stamp inside the DAO, find the lease expired and fail; the worker then regains
 * ownership only through a new acquisition with the next epoch (never a revival of the old lease).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaseRenewalFreshStampTest {

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
    void aRenewalDelayedPastExpiryFailsAndOwnershipReturnsOnlyThroughANewEpoch() throws Exception {
        FakeClock workerClock = new FakeClock(); // drives the worker's renewal timing only; the database stamps use h.clock
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withDelays(1500, 0, 0), workerClock, h.service);
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.BEFORE_RENEW, null, null);
        try {
            long id = p.accepted("LRF-1");
            assertTrue(h.events.await(EventType.ORDER_PREPARING, id, Duration.ofSeconds(20)), "the worker claimed the order and now cooks");
            // the renewal falls due on the worker's clock; advance until the worker (in its sleep slices) reaches the hook
            for (int i = 0; i < 100 && !held.blocked.await(100, TimeUnit.MILLISECONDS); i++) {
                workerClock.advance(Duration.ofSeconds(11));
            }
            held.awaitBlocked();                          // the worker holds its token and has not called the DAO yet
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id));
            String owner = h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id);
            assertEquals("BIRYANI#1", owner);

            h.clock.advance(Duration.ofSeconds(31)); // the lease expires while the worker is paused
            held.release.countDown();                // the DAO now stamps after the expiry

            p.awaitDelivered(id);
            assertEquals(1, h.events.countFor(EventType.LEASE_REACQUIRED, id), "ownership came back through acquireLease");
            assertEquals(2, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "a revival would have left epoch 1");
            assertEquals(0, h.events.countFor(EventType.LEASE_LOST, id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            held.release.countDown();
            p.stop();
        }
    }
}
