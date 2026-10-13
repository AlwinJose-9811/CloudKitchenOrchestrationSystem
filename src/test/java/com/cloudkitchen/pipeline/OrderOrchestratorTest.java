package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Dispatch contract (RR-11), start/stop, thread names and the dashboard views of the orchestrator. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderOrchestratorTest {

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
    void oneWorkerPerActiveStationRunsUnderItsOwnName() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            Set<String> names = PipelineHarness.stationThreads().stream().map(Thread::getName).collect(Collectors.toSet());
            assertEquals(Set.of("station-BIRYANI-worker", "station-BURGER-worker", "station-PIZZA-worker"), names);
            List<StationView> views = p.orch.stationViews();
            assertEquals(3, views.size());
            assertTrue(views.stream().allMatch(v -> v.queueCapacity() == 50 && !v.paused()));
        }
    }

    @Test
    void stoppingEndsEveryWorkerThreadAndReturnsEveryConnection() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        long id = p.accepted("ORCH-STOP-1");
        p.awaitDelivered(id);
        p.stop();
        p.assertNoStationThreads();
        assertEquals(0, h.db.database().activeConnections());
    }

    @Test
    void anAcceptedOrderIsDeliveredThroughTheRealQueue() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.accepted("ORCH-1");
            p.awaitDelivered(id);
            assertEquals("DELIVERED", p.status(id));
            p.assertEachEdgeOnce(id);
            p.assertStockLedger();
            assertEquals(1, h.metricsDelta().processed());
        }
    }

    @Test
    void anUnknownOrInactiveStationIsUnavailableAndNothingIsQueued() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            QueueFullException e = assertThrows(QueueFullException.class, () -> p.orch.dispatch(1L, "NO_SUCH_BRAND"));
            assertEquals(QueueFullException.Reason.STATION_UNAVAILABLE, e.getReason());
            assertThrows(QueueFullException.class, () -> p.orch.dispatch(1L, null));
        }
    }

    @Test
    void dispatchAfterStopIsRefusedAsClosed() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        p.stop();
        QueueFullException e = assertThrows(QueueFullException.class, () -> p.orch.dispatch(1L, "BIRYANI"));
        assertEquals(QueueFullException.Reason.QUEUE_CLOSED, e.getReason());
    }

    @Test
    void aFullQueueTimesOutWithoutInsertingAndAnInterruptBeforeInsertionKeepsTheFlag() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withQueue(1, 50));
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && owner.startsWith("BIRYANI")) {
                blocked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            long first = p.accepted("ORCH-FULL-1");
            assertTrue(blocked.await(10, TimeUnit.SECONDS));
            long second = p.accepted("ORCH-FULL-2"); // occupies the single queue slot
            StationRuntime runtime = p.orch.runtime("BIRYANI");
            assertEquals(1, runtime.size());
            long started = System.nanoTime();
            QueueFullException full = assertThrows(QueueFullException.class, () -> p.orch.dispatch(99_999L, "BIRYANI"));
            assertEquals(QueueFullException.Reason.QUEUE_FULL, full.getReason());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 45, "the offer waited for its timeout");
            assertEquals(1, runtime.size(), "the failed offer inserted nothing");

            Thread.currentThread().interrupt();
            QueueFullException interrupted = assertThrows(QueueFullException.class, () -> p.orch.dispatch(99_999L, "BIRYANI"));
            assertEquals(QueueFullException.Reason.INTERRUPTED, interrupted.getReason());
            assertTrue(Thread.interrupted(), "the interrupt flag was restored");
            assertEquals(1, runtime.size());
            release.countDown();
            p.awaitDelivered(first);
            p.awaitDelivered(second);
        } finally {
            release.countDown();
            p.stop();
        }
    }

    @Test
    void pauseHoldsTheWorkerBetweenOrdersAndResumeContinues() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            p.orch.pause("BIRYANI");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> p.workerState("BIRYANI") == WorkerState.PAUSED));
            long id = p.accepted("ORCH-PAUSE-1");
            assertEquals(1, p.orch.runtime("BIRYANI").size());
            assertEquals("ROUTED", p.status(id), "the submitter routed it; nobody has claimed it");
            assertTrue(p.orch.stationViews().stream().filter(v -> v.stationCode().equals("BIRYANI")).findFirst().orElseThrow().paused());
            p.orch.resume("BIRYANI");
            p.awaitDelivered(id);
            assertFalse(p.orch.runtime("BIRYANI").isPaused());
            assertEquals(OrderStatus.DELIVERED.name(), p.status(id));
        }
    }
}
