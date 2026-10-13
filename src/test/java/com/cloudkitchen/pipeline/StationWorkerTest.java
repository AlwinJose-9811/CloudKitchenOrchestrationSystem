package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The lifecycle a worker drives, resumption from every non-terminal status, and the three skip rules (RR-3..RR-6). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationWorkerTest {

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

    @Test
    void anOrderWalksTheWholeLifecycleAndEveryEdgeIsRecordedOnceWithTheWorkerAsActor() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.accepted("SW-1");
            p.awaitDelivered(id);
            assertEquals(FULL_CHAIN, p.edges(id));
            p.assertEachEdgeOnce(id);
            List<String> actors = p.actors(id);
            assertEquals(List.of("worker:BIRYANI#1"), actors.subList(4, 5).stream().distinct().toList(), "PREPARING was claimed by the worker");
            assertTrue(actors.subList(3, actors.size()).stream().allMatch(a -> a.equals("worker:BIRYANI#1")), actors.toString());
            assertEquals(1, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "one lease was taken");
            assertNull(p.h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "the terminal step cleared the lease");
            assertNull(p.h.t.text("SELECT lease_expires_at FROM orders WHERE id = ?", id));
            p.assertStockLedger();
            assertEquals(1, h.metricsDelta().processed());
            assertEquals(1, h.metricsDelta().processingSamples());
            assertEquals(1L, p.orch.stationViews().stream().filter(v -> v.stationCode().equals("BIRYANI")).findFirst().orElseThrow()
                    .worker().processedCount());
        }
    }

    @Test
    void aWorkerThatReadsValidatedRoutesTheOrderItselfWithTheDocumentedNote() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.persistedAt("SW-ROUTE", OrderStatus.VALIDATED);
            p.orch.enqueueRecovered(id, "BIRYANI");
            p.awaitDelivered(id);
            assertEquals("ROUTED_BY_WORKER", p.h.t.text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
            assertEquals("worker:BIRYANI#1", p.h.t.text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'ROUTED'", id));
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"VALIDATED", "ROUTED", "PREPARING", "PACKED", "OUT_FOR_DELIVERY"})
    void anOrderFoundInAnyNonTerminalStatusIsResumedToDelivered(OrderStatus status) throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.persistedAt("SW-RESUME-" + status, status);
            List<String> before = p.edges(id);
            p.orch.enqueueRecovered(id, "BIRYANI");
            p.awaitDelivered(id);
            assertEquals(FULL_CHAIN, p.edges(id));
            assertEquals(before, p.edges(id).subList(0, before.size()), "history before the restart point is untouched");
            p.assertEachEdgeOnce(id);
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED", "FAILED"})
    void aTerminalOrderIsSkippedWithoutAnyWrite(OrderStatus status) throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.persistedAt("SW-TERM-" + status, status);
            List<String> before = p.edges(id);
            p.orch.enqueueRecovered(id, "BIRYANI");
            assertTrue(h.events.await(EventType.ORDER_SKIPPED_TERMINAL, id, Duration.ofSeconds(20)));
            assertEquals(status.name(), p.status(id));
            assertEquals(before, p.edges(id));
            assertEquals(0, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "no lease was ever taken");
            assertEquals(0, h.metricsDelta().processed());
        }
    }

    @Test
    void anOrderAwaitingCompensationIsNeverLeasedRoutedOrProcessed() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.persistedAt("SW-PENDING", OrderStatus.VALIDATED);
            assertTrue(h.t.ok(c -> h.t.orders.markCompensationPending(c, id, "QUEUE_FULL", h.clock.instant().plusSeconds(60))));
            p.orch.enqueueRecovered(id, "BIRYANI");
            assertTrue(h.events.await(EventType.ORDER_SKIPPED_PENDING_COMPENSATION, id, Duration.ofSeconds(20)));
            assertEquals("VALIDATED", p.status(id));
            assertEquals(0, p.count("SELECT claim_epoch FROM orders WHERE id = ?", id));
            assertEquals(2, p.edges(id).size());
        }
    }

    @Test
    void anOrderWhoseLeaseIsHeldByAnotherOwnerIsSkippedAndLeftAlone() throws Exception {
        try (PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast())) {
            long id = p.persistedAt("SW-LEASED", OrderStatus.ROUTED);
            FencingToken other = h.service.acquireLease(id, "OTHER#9").orElseThrow();
            p.orch.enqueueRecovered(id, "BIRYANI");
            assertTrue(h.events.await(EventType.ORDER_SKIPPED_LEASED, id, Duration.ofSeconds(20)));
            assertEquals("ROUTED", p.status(id));
            assertEquals(other.epoch(), p.count("SELECT claim_epoch FROM orders WHERE id = ?", id), "the foreign lease is untouched");
            assertEquals("OTHER#9", p.h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id));
        }
    }
}
