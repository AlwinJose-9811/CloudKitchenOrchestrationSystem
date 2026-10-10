package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/** Invariant I-5: of many callers of one conditional transition exactly one changes the row and has side effects. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionalTransitionRaceTest {

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
    void eightCallersOfTheSameTransitionProduceOneWinnerOneHistoryRowAndOneEvent() throws Exception {
        h.reset();
        java.util.Map<Long, Integer> initial = com.cloudkitchen.testsupport.PipelineHarness.readStock(h);
        SubmissionResult r = h.service.submitOrder(h.t.request(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "CT-1", "BIRYANI", "Chicken Biryani", 1));
        long id = r.orderId();
        h.events.clear();
        ExecutorService executor = Executors.newFixedThreadPool(8, t -> new Thread(t, "test-transition"));
        try {
            CyclicBarrier barrier = new CyclicBarrier(8);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String actor = "T" + i;
                futures.add(executor.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    return h.service.transition(id, OrderStatus.ROUTED, OrderStatus.PREPARING, actor, null);
                }));
            }
            int winners = 0;
            for (Future<Boolean> f : futures) {
                winners += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, winners, "exactly one caller changed the row");
            assertEquals(1, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'PREPARING'", id));
            assertEquals(1, h.events.countFor(EventType.ORDER_PREPARING, id), "only the winner emitted the event");
            assertEquals("PREPARING", h.t.text("SELECT status FROM orders WHERE id = ?", id));
            com.cloudkitchen.testsupport.PipelineHarness.assertEachEdgeOnce(h, id);
            com.cloudkitchen.testsupport.PipelineHarness.assertStockLedger(h, initial);
        } finally {
            executor.shutdownNow();
        }
    }
}
