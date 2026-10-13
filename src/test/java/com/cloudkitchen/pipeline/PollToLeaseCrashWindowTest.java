package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RW-3, RW-4, P1..P5 (SDD 7.11.14): a worker killed at an exact point is replaced by one monitor pass, and the order
 * it had taken is delivered once, without advancing the clock towards any orphan threshold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PollToLeaseCrashWindowTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @ParameterizedTest(name = "death at {0}/{1}")
    @CsvSource({"AFTER_POLL,", "AFTER_LEASE,", "BEFORE_STEP,ROUTED", "AFTER_STEP,ROUTED", "BEFORE_STEP,PREPARING", "AFTER_STEP,PACKED"})
    void aWorkerKilledAtAnyPointIsReplacedByOnePassAndTheOrderIsDeliveredOnce(WorkerHook.Stage stage, OrderStatus step) throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        AtomicBoolean fired = new AtomicBoolean();
        p.orch.setWorkerHook((s, owner, id, st) -> {
            if (s == stage && (step == null || st == step) && owner.equals("BIRYANI#1") && fired.compareAndSet(false, true)) {
                throw new SimulatedWorkerDeath(s + "/" + st);
            }
        });
        try {
            long id = p.accepted("PLC-" + stage + "-" + step);
            ExecutionException death = assertThrows(ExecutionException.class, () -> p.orch.runtime("BIRYANI").future().get(20, TimeUnit.SECONDS));
            assertInstanceOf(SimulatedWorkerDeath.class, death.getCause());
            assertTrue(p.orch.runtime("BIRYANI").future().isDone());
            String before = p.status(id);
            assertTrue(!before.equals("DELIVERED"));

            p.orch.monitor().runOnce(); // the clock did not move: no orphan threshold is involved

            p.awaitDelivered(id);
            assertEquals(1, h.events.all().stream().filter(e -> e.type() == EventType.WORKER_DIED).count());
            assertEquals(1, h.events.all().stream().filter(e -> e.type() == EventType.WORKER_RESTARTED).count());
            assertEquals(2, p.orch.runtime("BIRYANI").currentGeneration());
            p.assertEachEdgeOnce(id);
            assertEquals(1, h.metricsDelta().processed());
            assertEquals(0, h.metricsDelta().failed());
            p.assertStockLedger();
        } finally {
            p.stop();
        }
    }
}
