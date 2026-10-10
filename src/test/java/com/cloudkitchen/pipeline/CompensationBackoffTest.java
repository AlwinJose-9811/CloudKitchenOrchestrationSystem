package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.OrderValidator;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 7.10.8: next attempt = now + min(base x 2^attempts, cap); an alert from the configured attempt count; never abandoned. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationBackoffTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private long secondsUntilNext(long id) throws Exception {
        LocalDateTime next = LocalDateTime.parse(h.t.text("SELECT compensation_next_at FROM orders WHERE id = ?", id).replace(' ', 'T'));
        return Duration.between(LocalDateTime.ofInstant(h.clock.instant(), ZoneOffset.UTC), next).toSeconds();
    }

    @Test
    void theDelayDoublesPerFailedAttemptUpToTheCapAndTheAlertStartsAtTheConfiguredAttempt() throws Exception {
        h.reset();
        OrderService.Settings base = OrderService.Settings.from(h.db.config());
        // base 30 s, cap 100 s, alert from the 4th attempt
        OrderService.Settings settings = new OrderService.Settings(base.leaseTtl(), 0, Duration.ofSeconds(30), Duration.ofSeconds(100), 4, base.leaseStampRetry());
        OrderService service = new OrderService(h.db.database(), h.t.orders, h.stockFaults.dao(), h.t.history, h.t.menu,
                new OrderValidator(), h.routing, new FakeDispatchers.AlwaysFull(QueueFullException.Reason.QUEUE_FULL), new InFlightRegistry(),
                new MetricsRegistry(), h.events, h.clock, SubmissionHook.NO_OP, settings);
        service.setAccepting(true);
        h.stockFaults.failAlways("restore", PipelineHarness.outage());
        SubmissionResult r = service.submitOrder(h.t.request(OrderSource.SWIGGY, "CB-1", "BIRYANI", "Chicken Biryani", 1));
        long id = r.orderId();
        assertEquals(30, secondsUntilNext(id), "the marker is due after the base delay");
        assertEquals("1", h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));

        long[] expectedDelays = {60, 100, 100, 100}; // 30 x 2^1, then capped
        for (int attempt = 0; attempt < expectedDelays.length; attempt++) {
            h.clock.advance(Duration.ofSeconds(1000));
            assertEquals(false, service.completePendingCompensation(id));
            assertEquals(expectedDelays[attempt], secondsUntilNext(id), "after failed attempt " + (attempt + 2));
            assertEquals(String.valueOf(attempt + 2), h.t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));
            long stuck = h.events.countFor(EventType.COMPENSATION_STUCK, id);
            if (attempt + 2 < 4) {
                assertEquals(0, stuck, "no alert before the configured attempt count");
            } else {
                assertTrue(stuck >= 1, "alert from attempt 4 on");
            }
        }
        assertEquals("PENDING", h.t.text("SELECT compensation_state FROM orders WHERE id = ?", id), "never abandoned");
        assertEquals("VALIDATED", h.t.text("SELECT status FROM orders WHERE id = ?", id), "never failed without the restore");
        h.stockFaults.clearFaults();
        assertTrue(service.completePendingCompensation(id));
        assertEquals("FAILED", h.t.text("SELECT status FROM orders WHERE id = ?", id));
    }

    @Test
    void theDueQueryHonoursTheBackoffTime() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long id = p.pendingOrder("CB-2");
            assertEquals(java.util.List.of(), h.service.findDuePendingCompensationIds(10), "not due inside the backoff");
            assertEquals(java.util.List.of(id), h.service.findPendingCompensationIds(), "the startup query ignores the backoff");
            p.advance(Duration.ofSeconds(31));
            assertEquals(java.util.List.of(id), h.service.findDuePendingCompensationIds(10));
        } finally {
            p.stop();
        }
    }
}
