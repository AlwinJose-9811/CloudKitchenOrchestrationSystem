package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.9: the sweeper hands back genuine orphans and nothing else, and one bad pass never ends the schedule. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrphanSweeperTest {

    static final OrphanSweeper.Settings SETTINGS = new OrphanSweeper.Settings(true, 3600, 60, 120, 100);

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private OrphanSweeper sweeperOf(PipelineHarness p, OrphanSweeper.Settings settings) {
        return new OrphanSweeper(h.service, p.orch, new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE),
                settings, h.clock, h.events);
    }

    private static void pauseAndWait(PipelineHarness p, String code) {
        p.orch.pause(code);
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState(code) == WorkerState.PAUSED));
    }

    @Test
    void onlyAGenuineOrphanIsHandedBackAndEverythingThatIsLegitimatelyIdleIsLeftAlone() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            pauseAndWait(p, "BIRYANI");
            long orphan = p.persistedAt("SW-ORPHAN", OrderStatus.PREPARING);       // nobody holds it, nobody queued it
            long queued = p.persistedAt("SW-QUEUED", OrderStatus.ROUTED);
            p.orch.enqueueRecovered(queued, "BIRYANI");                              // waiting for a healthy worker
            long pending = p.persistedAt("SW-PENDING", OrderStatus.VALIDATED);
            assertTrue(h.t.ok(c -> h.t.orders.markCompensationPending(c, pending, "QUEUE_FULL", h.clock.instant().plusSeconds(600))));
            long done = p.persistedAt("SW-DONE", OrderStatus.DELIVERED);
            p.advance(Duration.ofSeconds(121));                                      // all of the above are now older than the threshold
            long fresh = p.persistedAt("SW-FRESH", OrderStatus.ROUTED);              // just touched
            long leased = p.persistedAt("SW-LEASED", OrderStatus.PACKED);
            h.service.acquireLease(leased, "OTHER#1").orElseThrow();                 // a live worker's valid lease
            OrphanSweeper sweeper = sweeperOf(p, SETTINGS);
            h.orderFaults.resetCounters();

            sweeper.reenqueueOrphans();

            assertEquals(Set.of(orphan, queued), p.orch.runtime("BIRYANI").physicalSnapshot());
            assertEquals(2, p.orch.runtime("BIRYANI").size(), "the order that was already queued was not queued twice");
            assertTrue(Set.of(pending, done, fresh, leased).stream().noneMatch(p.orch.runtime("BIRYANI").physicalSnapshot()::contains));
            assertEquals(1, sweeper.stats().reenqueued());
            assertEquals(1, h.events.countFor(EventType.ORDER_REENQUEUED, orphan));
            assertTrue(h.orderFaults.sequence().stream().noneMatch(m -> m.equals("transition") || m.contains("Lease") || m.equals("claimStockRestore")),
                    "the sweeper's own database effect is limited to reads here: " + h.orderFaults.sequence());
            p.orch.resume("BIRYANI");
            p.awaitDelivered(orphan);
            p.assertEachEdgeOnce(orphan);
        } finally {
            p.stop();
        }
    }

    @Test
    void anOrderOfAMissingStationIsFailedWithTheDocumentedNote() throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        long id = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "SW-NOSTATION", "PIZZA", "Margherita", 1)).orderId();
        h.routing.refresh(h.db.database().withConnection(h.t.stations::findAllActive).stream().filter(s -> !s.getCode().equals("PIZZA")).toList());
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            p.advance(Duration.ofSeconds(121));
            sweeperOf(p, SETTINGS).reenqueueOrphans();
            assertEquals("FAILED", p.status(id));
            assertEquals("STATION_UNAVAILABLE", h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
            p.assertStockLedger();
        } finally {
            h.routing.refresh(h.db.database().withConnection(h.t.stations::findAllActive));
            p.stop();
        }
    }

    @Test
    void aFullQueueStopsTheStationForThePassAndTheNextPassContinues() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(1, 50));
        try {
            pauseAndWait(p, "BIRYANI");
            long a = p.persistedAt("SW-FULL-A", OrderStatus.ROUTED);
            long b = p.persistedAt("SW-FULL-B", OrderStatus.ROUTED);
            p.advance(Duration.ofSeconds(121));
            OrphanSweeper sweeper = sweeperOf(p, SETTINGS);
            sweeper.reenqueueOrphans();
            assertEquals(Set.of(a), p.orch.runtime("BIRYANI").physicalSnapshot());
            assertEquals(1, sweeper.stats().reenqueued());
            p.orch.resume("BIRYANI");
            p.awaitDelivered(a);
            sweeper.reenqueueOrphans();
            p.awaitDelivered(b);
            assertEquals(2, sweeper.stats().reenqueued());
        } finally {
            p.stop();
        }
    }

    @Test
    void aPassDoesNothingWhileTheApplicationIsNotAcceptingOrTheSweeperIsDisabled() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            pauseAndWait(p, "BIRYANI");
            p.persistedAt("SW-OFF", OrderStatus.ROUTED);
            p.advance(Duration.ofSeconds(121));
            OrphanSweeper sweeper = sweeperOf(p, SETTINGS);
            h.service.setAccepting(false);
            sweeper.runOnce();
            assertEquals(0, sweeper.stats().passes(), "recovering or shutting down: the pass returns at once");
            assertEquals(0, p.orch.runtime("BIRYANI").size());
            h.service.setAccepting(true);
            OrphanSweeper disabled = sweeperOf(p, new OrphanSweeper.Settings(false, 3600, 60, 120, 100));
            disabled.runOnce();
            disabled.start();
            assertEquals(0, disabled.stats().passes());
            assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("reconcile-scheduler") && t.isAlive()));
        } finally {
            h.service.setAccepting(true);
            p.stop();
        }
    }

    @Test
    void aFailingPassIsLoggedAndCountedAndTheNextPassRunsNormally() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            OrphanSweeper sweeper = sweeperOf(p, SETTINGS);
            h.orderFaults.failAlways("findStaleNonTerminal", PipelineHarness.outage());
            sweeper.runOnce();
            sweeper.runOnce();
            assertEquals(2, sweeper.stats().passes());
            h.orderFaults.clearFaults();
            long id = p.persistedAt("SW-AFTER", OrderStatus.ROUTED);
            p.advance(Duration.ofSeconds(121));
            pauseAndWait(p, "BIRYANI");
            sweeper.runOnce();
            assertEquals(3, sweeper.stats().passes());
            assertTrue(p.orch.runtime("BIRYANI").physicalSnapshot().contains(id));
            p.orch.resume("BIRYANI");
            p.awaitDelivered(id);
        } finally {
            p.stop();
        }
    }

    @Test
    void theScheduledSweeperRunsUnderItsOwnNameAndStopsIdempotently() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        OrphanSweeper sweeper = sweeperOf(p, SETTINGS);
        try {
            sweeper.start();
            sweeper.start(); // a second start does nothing
            Thread thread = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("reconcile-scheduler")).findFirst().orElseThrow();
            sweeper.stop();
            sweeper.stop();
            thread.join(5000);
            assertFalse(thread.isAlive());
        } finally {
            sweeper.stop();
            p.stop();
        }
    }
}
