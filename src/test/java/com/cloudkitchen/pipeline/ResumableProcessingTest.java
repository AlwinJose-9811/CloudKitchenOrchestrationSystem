package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FR-RES-03 / SDD 7.8.10 scenario 7: a restarted pipeline continues every order from the status it finds, with the real stock held. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResumableProcessingTest {

    private static final List<String> FULL_CHAIN = List.of("null->PLACED", "PLACED->VALIDATED", "VALIDATED->ROUTED",
            "ROUTED->PREPARING", "PREPARING->PACKED", "PACKED->OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY->DELIVERED");

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"VALIDATED", "ROUTED", "PREPARING", "PACKED", "OUT_FOR_DELIVERY"})
    void anOrderLeftInAnyStatusByACrashedProcessIsCompletedByTheNextOne(OrderStatus status) throws Exception {
        h.reset();
        Map<Long, Integer> initial = PipelineHarness.readStock(h);
        // a previous run: the order exists with its stock held (no worker, accepting fake dispatcher) and a lease left behind
        PipelineHarness previous = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        previous.crash();
        h.dispatcher.use(new com.cloudkitchen.testsupport.FakeDispatchers.Accepting());
        long id = previous.heldAt("RP-" + status, status);
        if (status != OrderStatus.VALIDATED) {
            h.service.acquireLease(id, "BIRYANI#1"); // the dead worker's lease, still valid in the database
        }
        List<String> before = previous.edges(id);
        int heldItems = (int) previous.count("SELECT stock_qty FROM inventory WHERE item_id = ?", h.t.itemId("Chicken Biryani"));

        PipelineHarness next = previous.restart(PipelineHarness.fast());
        try {
            new RecoveryService(h.service, next.orch, h.events, RecoveryService.SnapshotStep.NONE).recover();
            next.awaitDelivered(id);
            assertEquals(FULL_CHAIN, next.edges(id));
            assertEquals(before, next.edges(id).subList(0, before.size()), "earlier history untouched, no earlier step repeated");
            assertEquals(1, next.count("SELECT stock_deducted FROM orders WHERE id = ?", id), "delivered orders keep their stock consumed");
            assertEquals(heldItems, next.count("SELECT stock_qty FROM inventory WHERE item_id = ?", h.t.itemId("Chicken Biryani")),
                    "no second deduction and no restore");
            next.assertEachEdgeOnce(id);
            next.assertStockLedger();
        } finally {
            next.stop();
        }
    }
}
