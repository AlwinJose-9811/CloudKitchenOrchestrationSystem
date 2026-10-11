package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * SDD 7.15.6: one order that cannot be handed back does not stop the orders after it, a transient database error is
 * retried a bounded number of times on the same thread without clearing leases again or queueing anything twice, and
 * an application whose recovery did not complete never starts accepting. Time never passes: the pause is a recorder.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveryRetryTest {

    private static final RecoveryService.Retry RETRY = new RecoveryService.Retry(3, Duration.ofMillis(100));

    private ServiceHarness h;
    private final List<Duration> pauses = new ArrayList<>();

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private RecoveryService recoveryOf(PipelineHarness p) {
        pauses.clear();
        return new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE, RETRY, pauses::add);
    }

    private static SQLException poison() {
        return new SQLException("poison row", "HY000", 9999);
    }

    /** Three ROUTED PIZZA orders whose station has no worker: recovery fails each one with STATION_UNAVAILABLE. */
    private List<Long> threeOrdersOfAStationWithoutAWorker() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            SubmissionResult r = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "RR-PZ" + i, "PIZZA", "Margherita", 1));
            assertEquals(SubmissionResult.Outcome.ACCEPTED, r.outcome());
            ids.add(r.orderId());
        }
        h.routing.refresh(h.db.database().withConnection(h.t.stations::findAllActive).stream().filter(s -> !s.getCode().equals("PIZZA")).toList());
        h.orderFaults.resetCounters();
        return ids;
    }

    private void restoreRouting() throws CloudKitchenException {
        h.routing.refresh(h.db.database().withConnection(h.t.stations::findAllActive));
    }

    @Test
    void aPermanentFailureOfOneOrderDoesNotStopTheOrdersAfterItAndRecoveryReportsItAsIncomplete() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        List<Long> ids = threeOrdersOfAStationWithoutAWorker();
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            h.orderFaults.failBefore("transition", 2, poison()); // the second order cannot be failed
            RecoveryService recovery = recoveryOf(p);
            CloudKitchenException incomplete = assertThrows(CloudKitchenException.class, recovery::recover);
            assertEquals("RECOVERY_INCOMPLETE", incomplete.getCode());

            assertEquals("FAILED", p.status(ids.get(0)));
            assertEquals("ROUTED", p.status(ids.get(1)), "the order that failed is untouched and still holds its stock");
            assertEquals("FAILED", p.status(ids.get(2)), "the order after the failing one was still processed");
            RecoveryService.Stats stats = recovery.stats();
            assertEquals(1, stats.orderFailures());
            assertEquals(1, stats.attempts(), "a permanent failure is not retried");
            assertEquals(2, stats.failedStationUnavailable());
            assertFalse(stats.succeeded());
            assertFalse(recovery.succeeded());
            assertFalse(stats.recovering(), "the run is over");
            assertEquals(List.of(), pauses, "nothing was waited for");
            p.assertStockLedger();

            // the operator fixes the cause and starts again: only the missing order is left to do
            h.orderFaults.clearFaults();
            recovery.recover();
            assertEquals("FAILED", p.status(ids.get(1)));
            assertTrue(recovery.succeeded());
            p.assertStockLedger();
            for (long id : ids) {
                p.assertEachEdgeOnce(id);
            }
        } finally {
            restoreRouting();
            p.stop();
        }
    }

    @Test
    void aTransientFailureOfOneOrderIsRetriedInTheNextRoundWithoutClearingLeasesOrTouchingTheOthers() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        List<Long> ids = threeOrdersOfAStationWithoutAWorker();
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            h.orderFaults.failBefore("transition", 2, PipelineHarness.outage());
            RecoveryService recovery = recoveryOf(p);
            recovery.recover();

            for (long id : ids) {
                assertEquals("FAILED", p.status(id));
                p.assertEachEdgeOnce(id);
            }
            RecoveryService.Stats stats = recovery.stats();
            assertEquals(2, stats.attempts(), "one failed round and one that finished the job");
            assertEquals(1, stats.orderFailures());
            assertEquals(3, stats.failedStationUnavailable(), "each order was failed once");
            assertTrue(stats.succeeded());
            assertEquals(List.of(Duration.ofMillis(100)), pauses, "one pause, the base backoff");
            assertEquals(1, h.orderFaults.calls("clearAllLeases"), "the leases were cleared once, not again in the second round");
            p.assertStockLedger();
        } finally {
            restoreRouting();
            p.stop();
        }
    }

    @Test
    void aTransientDatabaseErrorInAStepIsRetriedAndNothingIsQueuedOrProcessedTwice() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "RR-B" + i, "BIRYANI", "Chicken Biryani", 1)).orderId());
        }
        h.service.acquireLease(ids.get(0), "OLD#1").orElseThrow(); // a stale lease of the previous run
        h.orderFaults.resetCounters();
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            p.orch.pause("BIRYANI");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState("BIRYANI") == com.cloudkitchen.domain.enums.WorkerState.PAUSED));
            // the first lease clear and the first read of the order list fail with a connection error
            h.orderFaults.failBefore("clearAllLeases", 1, PipelineHarness.outage());
            h.orderFaults.failAlways("findNonTerminalForProcessing", PipelineHarness.outage());
            h.orderFaults.beforeCall("findNonTerminalForProcessing", 1, h.orderFaults::clearFaults);
            RecoveryService recovery = recoveryOf(p);
            recovery.recover();

            RecoveryService.Stats stats = recovery.stats();
            assertEquals(3, stats.attempts(), "lease clear failed, order list failed, then everything worked: the third round");
            assertTrue(stats.succeeded());
            assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), pauses, "doubling backoff");
            assertEquals(2, h.orderFaults.calls("clearAllLeases"), "cleared in the second round, where it first worked, and never again");
            assertEquals(3, stats.enqueued(), "each order was handed back exactly once");
            assertEquals(ids, List.copyOf(p.orch.runtime("BIRYANI").queue()), "oldest first, no duplicates");
            assertEquals(null, h.t.text("SELECT claimed_by FROM orders WHERE id = ?", ids.get(0)), "the stale lease was cleared");

            p.orch.resume("BIRYANI");
            for (long id : ids) {
                p.awaitDelivered(id);
                p.assertEachEdgeOnce(id);
            }
            assertEquals(3, h.metricsDelta().processed());
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }

    @Test
    void whenEveryRoundFailsRecoveryGivesUpAfterTheBoundAndTheApplicationNeverStartsAccepting() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        long id = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "RR-DOWN", "BIRYANI", "Chicken Biryani", 1)).orderId();
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            h.service.setAccepting(false); // as in a starting application
            h.orderFaults.failAlways("findNonTerminalForProcessing", PipelineHarness.outage());
            h.orderFaults.resetCounters();
            RecoveryService recovery = recoveryOf(p);
            AtomicBoolean opened = new AtomicBoolean();
            recovery.start(() -> {
                opened.set(true);
                h.service.setAccepting(true);
            });
            assertTrue(recovery.await(30, TimeUnit.SECONDS), "the recovery thread finished");

            assertFalse(opened.get(), "the action that opens the application never ran");
            assertFalse(h.service.isAccepting());
            SubmissionResult refused = h.service.submitOrder(h.t.request(OrderSource.ZOMATO, "RR-LATE", "BIRYANI", "Raita", 1));
            assertEquals(SubmissionResult.Outcome.REJECTED, refused.outcome(), "a submission is refused while recovery has not completed");
            assertFalse(recovery.succeeded());
            assertEquals(3, recovery.stats().attempts(), "bounded by the retry policy");
            assertEquals(3, h.orderFaults.calls("findNonTerminalForProcessing"));
            assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), pauses);
            assertEquals("ROUTED", p.status(id), "nothing was changed; the order waits for the next start");
            assertFalse(recovery.isRecovering());
        } finally {
            h.orderFaults.clearFaults();
            h.service.setAccepting(true);
            p.stop();
        }
    }

    @RepeatedTest(20)
    void stoppingRecoveryInterruptsAWaitBetweenRoundsAndLeavesTheOrdersInTheDatabase() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        long id = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "RR-STOP", "BIRYANI", "Chicken Biryani", 1)).orderId();
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            h.service.setAccepting(false);
            h.orderFaults.failAlways("findNonTerminalForProcessing", PipelineHarness.outage());
            java.util.concurrent.CountDownLatch inPause = new java.util.concurrent.CountDownLatch(1);
            RecoveryService recovery = new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE, RETRY, d -> {
                inPause.countDown();
                new java.util.concurrent.CountDownLatch(1).await(30, TimeUnit.SECONDS); // an interruptible wait like the real pause
            });
            AtomicBoolean opened = new AtomicBoolean();
            recovery.start(() -> opened.set(true));
            assertTrue(inPause.await(20, TimeUnit.SECONDS), "recovery is waiting between two rounds");
            assertTrue(recovery.isRecovering());
            recovery.stop();
            assertTrue(recovery.await(20, TimeUnit.SECONDS), "the interrupt ended the wait and the recovery thread");
            assertFalse(recovery.isRecovering());
            assertFalse(recovery.succeeded());
            assertFalse(opened.get());
            assertEquals("ROUTED", p.status(id));
        } finally {
            h.orderFaults.clearFaults();
            h.service.setAccepting(true);
            p.stop();
        }
    }

    @Test
    void aFailureWhileResolvingOnePendingCompensationDoesNotStopTheOtherAndIsRetried() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        h.stockFaults.failAlways("restore", PipelineHarness.outage());
        SubmissionResult first = h.service.submitOrder(h.t.request(OrderSource.ZOMATO, "RR-P1", "BIRYANI", "Raita", 1));
        SubmissionResult second = h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "RR-P2", "BIRYANI", "Raita", 2));
        assertFalse(first.compensated());
        assertFalse(second.compensated());
        h.stockFaults.clearFaults();
        h.dispatcher.use(new FakeDispatchers.Accepting());
        h.orderFaults.resetCounters();
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            h.orderFaults.failBefore("findState", 1, PipelineHarness.outage()); // the first pending order cannot even be read
            RecoveryService recovery = recoveryOf(p);
            recovery.recover();

            assertEquals("FAILED", p.status(first.orderId()));
            assertEquals("FAILED", p.status(second.orderId()));
            assertEquals(2, recovery.stats().compensationsResolved(), "each was resolved once");
            assertEquals(2, recovery.stats().attempts(), "the failing one was done in the second round, the other already in the first");
            assertEquals(1, recovery.stats().orderFailures());
            assertEquals(0, recovery.stats().compensationsStillPending());
            p.assertEachEdgeOnce(first.orderId());
            p.assertEachEdgeOnce(second.orderId());
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }
}
