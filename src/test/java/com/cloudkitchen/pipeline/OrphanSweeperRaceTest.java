package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** FR-RES-08: sweeper passes racing workers and cancels cause no duplicate edge, no double restore, no double count. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrphanSweeperRaceTest {

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
    void sweeperPassesRacingWorkersAndCancelsLeaveEveryOrderWithOneChainAndTheLedgerIntact() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withDelays(2, 1, 2));
        // threshold 1 s: after the clock moves by 2 s every unleased, unqueued, non-terminal order is a candidate
        OrphanSweeper sweeper = new OrphanSweeper(h.service, p.orch, new RecoveryService(h.service, p.orch, h.events, RecoveryService.SnapshotStep.NONE),
                new OrphanSweeper.Settings(true, 3600, 60, 1, 100), h.clock, h.events);
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-sweep-race"));
        try {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add(p.accepted("SRACE-" + i));
            }
            p.advance(Duration.ofSeconds(2));
            Future<?> sweeping = executor.submit(() -> {
                for (int i = 0; i < 15; i++) {
                    sweeper.runOnce();
                }
            });
            Future<Integer> cancelling = executor.submit(() -> {
                int cancelled = 0;
                for (int i = 0; i < 10; i += 3) {
                    cancelled += h.service.cancelOrder(ids.get(i), "staff") ? 1 : 0;
                }
                return cancelled;
            });
            sweeping.get(60, TimeUnit.SECONDS);
            int cancelled = cancelling.get(60, TimeUnit.SECONDS);
            for (long id : ids) {
                p.awaitTerminal(id);
                p.assertEachEdgeOnce(id);
            }
            assertEquals(10 - cancelled, h.metricsDelta().processed(), "every order that was not cancelled was processed exactly once");
            assertEquals(0, h.metricsDelta().failed());
            assertEquals(cancelled, p.count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED'"));
            assertEquals(cancelled, h.events.countFor(EventType.STOCK_RESTORED, null), "stock came back once per cancelled order and for nothing else");
            assertTrue(sweeper.stats().passes() >= 15);
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }
}
