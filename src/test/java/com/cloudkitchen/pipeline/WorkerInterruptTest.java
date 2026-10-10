package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** InterruptedException handling (SDD 7.3): the flag is restored, the worker exits, the order is left untouched. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerInterruptTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    /** A worker that is in the (very long) delivery step of one order. */
    private long orderInDelivery(PipelineHarness p, CountDownLatch inDelivery) throws Exception {
        p.orch.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_STEP && step == OrderStatus.PACKED) {
                inDelivery.countDown();
            }
        });
        return p.accepted("INT-" + System.nanoTime());
    }

    @Test
    void anInterruptedWorkerExitsCleanlyAndLeavesTheOrderWhereItWas() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withDelays(0, 0, 60_000));
        try {
            CountDownLatch inDelivery = new CountDownLatch(1);
            long id = orderInDelivery(p, inDelivery);
            assertTrue(inDelivery.await(20, TimeUnit.SECONDS));
            Thread worker = PipelineHarness.stationThreads().stream().filter(t -> t.getName().equals("station-BIRYANI-worker")).findFirst().orElseThrow();
            worker.interrupt();
            // the task ends by returning (an Error or an exception would make get() throw); the pool thread itself stays
            assertNull(p.orch.runtime("BIRYANI").future().get(10, TimeUnit.SECONDS), "the interrupted worker returned normally");
            assertEquals("OUT_FOR_DELIVERY", p.status(id), "the order was not failed, delivered or rolled back");
            assertEquals(0, h.metricsDelta().failed());
            assertEquals(0, h.metricsDelta().processed());
            assertEquals(WorkerState.STOPPED, p.workerState("BIRYANI"));
            p.assertEachEdgeOnce(id);
        } finally {
            p.stop();
        }
    }

    @Test
    void shutdownInterruptsABlockedWorkerAndEndsEveryThread() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast().withDelays(0, 0, 60_000));
        CountDownLatch inDelivery = new CountDownLatch(1);
        long id = orderInDelivery(p, inDelivery);
        assertTrue(inDelivery.await(20, TimeUnit.SECONDS));
        long started = System.nanoTime();
        assertTrue(p.orch.shutdownGracefully(Duration.ofMillis(100), Duration.ofMillis(100)), "all workers ended");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 15, "shutdown was bounded");
        p.assertNoStationThreads();
        assertEquals("OUT_FOR_DELIVERY", p.status(id), "unfinished work stays non-terminal in the database");
        assertEquals(0, h.db.database().activeConnections());
        p.stop();
    }

    @Test
    void anIdleWorkerStopsAtOnceWhenAskedToDrain() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        long started = System.nanoTime();
        assertTrue(p.orch.shutdownGracefully(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
        p.assertNoStationThreads();
        p.stop();
    }
}
