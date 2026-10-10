package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInstance;

/**
 * RW-11, FI-5: a worker dies at a random point while staff try to cancel and the monitor repairs. Whatever the
 * interleaving, the order ends DELIVERED or CANCELLED, once, with the stock ledger intact.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeadWorkerRaceTest {

    private record Point(WorkerHook.Stage stage, OrderStatus step) {
    }

    private static final List<Point> POINTS = List.of(
            new Point(WorkerHook.Stage.AFTER_POLL, null), new Point(WorkerHook.Stage.AFTER_LEASE, null),
            new Point(WorkerHook.Stage.BEFORE_STEP, OrderStatus.ROUTED), new Point(WorkerHook.Stage.AFTER_STEP, OrderStatus.ROUTED),
            new Point(WorkerHook.Stage.BEFORE_STEP, OrderStatus.PREPARING), new Point(WorkerHook.Stage.AFTER_STEP, OrderStatus.PREPARING),
            new Point(WorkerHook.Stage.BEFORE_STEP, OrderStatus.PACKED), new Point(WorkerHook.Stage.AFTER_STEP, OrderStatus.PACKED),
            new Point(WorkerHook.Stage.BEFORE_STEP, OrderStatus.OUT_FOR_DELIVERY));

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
    void aWorkerDyingAtARandomPointWhileStaffCancelAlwaysEndsInOneConsistentTerminalState(RepetitionInfo info) throws Exception {
        Random random = new Random(info.getCurrentRepetition()); // reproducible per repetition
        Point point = POINTS.get(random.nextInt(POINTS.size()));
        boolean cancelRaces = random.nextBoolean();
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == point.stage() && (point.step() == null || step == point.step()) && owner.equals("BIRYANI#1")
                    && fired.compareAndSet(false, true)) {
                h.events.poke(); // the waiter below also waits on this flag, which is not an event
                throw new SimulatedWorkerDeath(point.toString());
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-staff"));
        try {
            long id = p.accepted("DWR-" + info.getCurrentRepetition());
            Future<CancelOutcome> cancel = cancelRaces ? executor.submit(() -> h.service.cancelOrderDetailed(id, "staff")) : null;
            // either the worker reaches its death point, or the order already ended (the cancel won early)
            assertTrue(h.events.awaitUntil(PipelineHarness.WAIT, () -> fired.get() || terminal(id)), "neither death nor a terminal state");
            if (fired.get()) {
                ExecutionException death = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                        () -> p.orch.runtime("BIRYANI").future().get(20, TimeUnit.SECONDS));
                assertTrue(death.getCause() instanceof SimulatedWorkerDeath);
                p.orch.monitor().runOnce();
            }
            CancelOutcome outcome = cancel == null ? null : cancel.get(20, TimeUnit.SECONDS);
            p.awaitTerminal(id);
            String status = p.status(id);
            assertTrue(status.equals("DELIVERED") || status.equals("CANCELLED"), "unexpected final state " + status);
            if (status.equals("CANCELLED")) {
                assertEquals(CancelOutcome.CANCELLED, outcome);
                assertEquals(0, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
                assertEquals(1, h.events.countFor(EventType.STOCK_RESTORED, id));
            } else {
                assertEquals(1, p.count("SELECT stock_deducted FROM orders WHERE id = ?", id));
                assertEquals(0, h.events.countFor(EventType.STOCK_RESTORED, id));
            }
            assertEquals(0, h.metricsDelta().failed(), "no death is ever turned into a FAILED order");
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
        } finally {
            executor.shutdownNow();
            p.stop();
        }
    }

    private boolean terminal(long id) {
        return h.events.countFor(EventType.ORDER_DELIVERED, id) + h.events.countFor(EventType.ORDER_CANCELLED, id)
                + h.events.countFor(EventType.ORDER_FAILED, id) > 0;
    }
}
