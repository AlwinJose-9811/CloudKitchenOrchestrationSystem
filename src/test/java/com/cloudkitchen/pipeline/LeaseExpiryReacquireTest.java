package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** RW-2, rule L-5/L-6: a lease that merely expired is re-acquired once per session; a retired worker never acquires. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaseExpiryReacquireTest {

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
    void anExpiredButUnsupersededLeaseIsReacquiredWithTheNextEpochAndTheOrderIsDelivered() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        PipelineHarness.Held held = p.holdAt("BIRYANI", WorkerHook.Stage.BEFORE_STEP, null, OrderStatus.ROUTED);
        try {
            long id = p.accepted("LER-1");
            held.awaitBlocked();
            h.clock.advance(Duration.ofSeconds(31)); // the lease expires; nobody else touches the order
            held.release.countDown();
            p.awaitDelivered(id);
            assertEquals(1, h.events.countFor(EventType.LEASE_REACQUIRED, id), "exactly one re-acquisition");
            assertEquals(2, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "a new lease, not a revival");
            assertEquals(0, h.events.countFor(EventType.LEASE_LOST, id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            held.release.countDown();
            p.stop();
        }
    }

    @Test
    void theReacquireLimitParksTheOrderWhichIsThenResumedInAFreshSession() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withLease(10_000, 1));
        AtomicInteger arrivals = new AtomicInteger();
        CountDownLatch[] blocked = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.BEFORE_STEP && step == OrderStatus.ROUTED) {
                int n = arrivals.getAndIncrement();
                if (n < 2) {
                    blocked[n].countDown();
                    StaleWorkerWakeTest.hang(release[n]);
                }
            }
        });
        try {
            long id = p.accepted("LER-2");
            assertTrue(blocked[0].await(20, TimeUnit.SECONDS));
            h.clock.advance(Duration.ofSeconds(31));
            release[0].countDown(); // first expiry: re-acquire (1 of 1)
            assertTrue(blocked[1].await(20, TimeUnit.SECONDS));
            h.clock.advance(Duration.ofSeconds(31));
            release[1].countDown(); // second expiry: the limit is reached, the order is parked
            assertTrue(h.events.await(EventType.ORDER_STALLED, id, Duration.ofSeconds(20)), "parked after worker.reacquire.max");
            p.awaitDelivered(id);
            assertEquals(1, h.events.countFor(EventType.LEASE_REACQUIRED, id));
            assertEquals(3, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "acquire, re-acquire, then a fresh session");
            assertEquals(0, h.metricsDelta().failed(), "expiry never fails an order");
            p.assertEachEdgeOnce(id);
        } finally {
            release[0].countDown();
            release[1].countDown();
            p.stop();
        }
    }

    @Test
    void aRetiredWorkerNeverAcquiresAgainAndLeavesTheIdForItsSuccessor() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        CountDownLatch aBlocked = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);
        CountDownLatch bBlocked = new CountDownLatch(1);
        CountDownLatch bRelease = new CountDownLatch(1);
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.BEFORE_STEP && owner.equals("BIRYANI#1") && step == OrderStatus.ROUTED) {
                aBlocked.countDown();
                StaleWorkerWakeTest.hang(aRelease);
            } else if (stage == WorkerHook.Stage.AFTER_POLL && owner.equals("BIRYANI#2")) {
                bBlocked.countDown();
                StaleWorkerWakeTest.hang(bRelease);
            }
        });
        try {
            long id = p.accepted("LER-3");
            assertTrue(aBlocked.await(20, TimeUnit.SECONDS));
            StationWorker a = p.orch.runtime("BIRYANI").worker();
            assertTrue(p.orch.restartWorker("BIRYANI", 1, "test"), "A is retired and its lease cleared");
            assertTrue(bBlocked.await(20, TimeUnit.SECONDS), "B polled the re-offered id and is held before it can lease");
            aRelease.countDown();
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), a::hasExited), "A stood down");
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "A did not take a second lease");
            assertEquals(0, h.events.countFor(EventType.LEASE_REACQUIRED, id));
            bRelease.countDown();
            p.awaitDelivered(id);
            assertEquals(2, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "only B acquired afterwards");
            p.assertEachEdgeOnce(id);
        } finally {
            aRelease.countDown();
            bRelease.countDown();
            p.stop();
        }
    }
}
