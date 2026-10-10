package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** NFR-REL-01: after an abrupt stop and a restart no accepted order is missing or stuck, and stock is exact. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrashRecoveryTest {

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
    void everyAcceptedOrderOfAProcessThatDiedMidFlightIsDeliveredByTheNextOne() throws Exception {
        PipelineHarness first = PipelineHarness.start(h, PipelineHarness.fast());
        // BIRYANI: the worker stops right after it claimed the first order (it holds a valid lease in PREPARING) while
        // three more orders wait in its queue; BURGER finishes its orders; PIZZA gets an order whose submitter died after TX1.
        PipelineHarness.Held held = first.holdAt("BIRYANI", WorkerHook.Stage.AFTER_STEP, null, OrderStatus.ROUTED);
        List<Long> accepted = new ArrayList<>();
        accepted.add(first.accepted("CR-B1"));
        held.awaitBlocked();
        for (int i = 2; i <= 4; i++) {
            accepted.add(first.accepted("CR-B" + i));
        }
        List<Long> burgers = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            long id = first.submit(OrderSource.ZOMATO, "CR-G" + i, "BURGER", "Classic Burger", 1).orderId();
            burgers.add(id);
            accepted.add(id);
        }
        for (long id : burgers) {
            first.awaitDelivered(id);
        }
        h.hook.set(stage -> {
            if (stage == com.cloudkitchen.service.SubmissionHook.Stage.AFTER_TX1) {
                throw new com.cloudkitchen.testsupport.SimulatedCrash("after TX1");
            }
        });
        try {
            org.junit.jupiter.api.Assertions.assertThrows(com.cloudkitchen.testsupport.SimulatedCrash.class,
                    () -> first.submit(OrderSource.IN_HOUSE, "CR-P1", "PIZZA", "Margherita", 1));
        } finally {
            h.hook.set(com.cloudkitchen.service.SubmissionHook.NO_OP);
        }
        accepted.add(first.lastValidatedId());
        assertEquals("PREPARING", first.status(accepted.get(0)));
        first.crash();
        held.release.countDown();

        PipelineHarness second = first.restart(PipelineHarness.fast());
        try {
            RecoveryService recovery = new RecoveryService(h.service, second.orch, h.events, RecoveryService.SnapshotStep.NONE);
            recovery.recover();
            for (long id : accepted) {
                second.awaitDelivered(id);
                second.assertEachEdgeOnce(id);
            }
            assertEquals(accepted.size(), second.count("SELECT COUNT(*) FROM orders"), "no accepted order is missing");
            assertEquals(accepted.size(), second.count("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"), "none is stuck");
            assertEquals(0, second.count("SELECT COUNT(*) FROM orders WHERE stock_deducted = 0"), "no restore happened for a delivered order");
            assertTrue(recovery.stats().enqueued() >= 5, "the unfinished ones were handed back: " + recovery.stats());
            second.assertStockLedger();
        } finally {
            second.stop();
        }
    }
}
