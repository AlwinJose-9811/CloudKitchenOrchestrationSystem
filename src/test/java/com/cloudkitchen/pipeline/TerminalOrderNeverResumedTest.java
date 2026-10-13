package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.exception.InvalidStatusTransitionException;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Invariant I-3: nothing can bring a terminal order back (reconcile, workers, conditional updates). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminalOrderNeverResumedTest {

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
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED", "FAILED"})
    void noMechanismMovesOrReoffersATerminalOrder(OrderStatus terminal) throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.persistedAt("TNR-" + terminal, terminal);
            List<String> before = p.edges(id);
            long stationId = h.t.station("BIRYANI").getId();

            assertFalse(h.service.findReconcilableIds(stationId, Duration.ZERO, 100).contains(id), "reconcile never offers a terminal order");
            assertTrue(h.service.acquireLease(id, "BIRYANI#1").isEmpty(), "a terminal order cannot be leased");
            for (OrderStatus target : OrderStatus.values()) {
                assertThrows(InvalidStatusTransitionException.class, () -> h.service.transition(id, terminal, target, "TEST", null),
                        terminal + " -> " + target + " is outside the lifecycle");
            }
            assertEquals(terminal.name(), p.status(id));
            assertEquals(before, p.edges(id));
            // even a stale caller that believes the order is in a live status changes nothing
            assertFalse(h.service.transition(id, OrderStatus.ROUTED, OrderStatus.PREPARING, "STALE", null));
            assertFalse(h.service.transition(id, OrderStatus.VALIDATED, OrderStatus.CANCELLED, "STALE", null));
            assertEquals(before, p.edges(id));
        }
    }
}
