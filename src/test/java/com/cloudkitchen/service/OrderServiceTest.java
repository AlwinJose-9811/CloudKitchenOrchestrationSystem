package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.InHouseOrder;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.SwiggyOrder;
import com.cloudkitchen.domain.ZomatoOrder;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FieldError;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.dto.SubmissionResult.Outcome;
import com.cloudkitchen.exception.InvalidStatusTransitionException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @BeforeEach
    void clean() throws Exception {
        h.reset();
    }

    private SubmissionResult submit(OrderRequest request) {
        return h.service.submitOrder(request);
    }

    private OrderRequest swiggy(String ref, Object... items) throws Exception {
        return h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", items.length == 0 ? new Object[] {"Chicken Biryani", 2, "Raita", 1} : items);
    }

    private Order load(long id) throws Exception {
        return h.t.db.database().withConnection(c -> h.t.orders.findById(c, id)).orElseThrow();
    }

    private List<OrderStatus> edges(long id) throws Exception {
        return h.t.db.database().withConnection(c -> h.t.history.findByOrderId(c, id)).stream().map(StatusHistoryEntry::to).toList();
    }

    private void assertStockLedger() throws Exception {
        com.cloudkitchen.testsupport.TestSupport.assertStockLedger(h.t, 15 * 50);
    }

    // ---- FR-ORD-01: one entry point for every source ----

    @Test
    void oneValidRequestPerSourceYieldsThreePersistedOrdersOfTheMatchingSubclass() throws Exception {
        SubmissionResult a = submit(h.t.request(OrderSource.SWIGGY, "SW-1", "BIRYANI", "Chicken Biryani", 2, "Raita", 1));
        SubmissionResult b = submit(h.t.request(OrderSource.ZOMATO, "ZO-1", "BURGER", "Classic Burger", 1));
        SubmissionResult c = submit(h.t.request(OrderSource.IN_HOUSE, "", "PIZZA", "Margherita", 3));
        for (SubmissionResult r : List.of(a, b, c)) {
            assertEquals(Outcome.ACCEPTED, r.outcome());
            assertTrue(r.orderId() > 0);
        }
        assertInstanceOf(SwiggyOrder.class, load(a.orderId()));
        assertInstanceOf(ZomatoOrder.class, load(b.orderId()));
        Order inHouse = load(c.orderId());
        assertInstanceOf(InHouseOrder.class, inHouse);
        assertTrue(inHouse.getExternalRef().startsWith("IH-"), "generated reference for a blank in-house one");
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(3, h.metricsDelta().submitted());
        assertEquals(0, h.metricsDelta().rejected());
    }

    @Test
    void anAcceptedOrderIsRoutedWithItsFullHistoryAndStockDeductedOnce() throws Exception {
        SubmissionResult r = submit(swiggy("SW-2"));
        Order order = load(r.orderId());
        assertEquals(OrderStatus.ROUTED, order.getStatus());
        assertEquals(List.of(OrderStatus.PLACED, OrderStatus.VALIDATED, OrderStatus.ROUTED), edges(r.orderId()));
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
        assertEquals(49, h.t.stockOf("Raita"));
        assertEquals("1", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", r.orderId()));
        assertEquals("547.00", order.getSubtotal().toPlainString());
        assertEquals(List.of(EventType.ORDER_VALIDATED, EventType.ORDER_ROUTED), h.events.types());
        assertStockLedger();
    }

    // ---- FR-ORD-07: rejection leaves nothing behind ----

    @Test
    void aValidationFailureLeavesTheDatabaseUntouchedAndCountsAsRejected() throws Exception {
        OrderRequest bad = new OrderRequest(OrderSource.SWIGGY, "bad ref", "SUSHI", "", null, null, List.of());
        SubmissionResult r = submit(bad);
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(SubmissionResult.VALIDATION, r.reasonCode());
        assertNull(r.orderId());
        assertTrue(r.errors().stream().map(FieldError::code).toList().containsAll(List.of("EXTERNAL_REF_INVALID", "BRAND_UNKNOWN", "CUSTOMER_NAME_INVALID")));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_status_history"));
        assertEquals(750, h.t.number("SELECT SUM(stock_qty) FROM inventory"));
        assertEquals(1, h.metricsDelta().rejected());
        assertEquals(List.of(EventType.ORDER_REJECTED), h.events.types());
    }

    @Test
    void aNullRequestIsRejectedNotAnException() {
        SubmissionResult r = submit(null);
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals("REQUEST_NULL", r.errors().get(0).code());
    }

    @Test
    void insufficientStockRejectsWholeOrderAndLeavesEveryItemUnchanged() throws Exception {
        h.t.execute("UPDATE inventory SET stock_qty = 1 WHERE item_id = ?", h.t.itemId("Raita"));
        // Chicken Biryani (id lower) is deducted first and has plenty; Raita (qty 2) then fails.
        SubmissionResult r = submit(h.t.request(OrderSource.SWIGGY, "SW-3", "BIRYANI", "Chicken Biryani", 5, "Raita", 2));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(SubmissionResult.INSUFFICIENT_STOCK, r.reasonCode());
        assertEquals(50, h.t.stockOf("Chicken Biryani"), "FR-INV-05: the enough-stock line was rolled back too");
        assertEquals(1, h.t.stockOf("Raita"));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_items"));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_status_history"));
        assertEquals(1, h.metricsDelta().rejected());
    }

    @Test
    void theExactRemainingStockCanBeBoughtAndOneMoreCannot() throws Exception {
        h.t.execute("UPDATE inventory SET stock_qty = 3 WHERE item_id = ?", h.t.itemId("Cola"));
        assertEquals(Outcome.ACCEPTED, submit(h.t.request(OrderSource.IN_HOUSE, "", "BURGER", "Cola", 3)).outcome());
        assertEquals(0, h.t.stockOf("Cola"));
        assertEquals(SubmissionResult.INSUFFICIENT_STOCK, submit(h.t.request(OrderSource.IN_HOUSE, "", "BURGER", "Cola", 1)).reasonCode());
        assertEquals(0, h.t.stockOf("Cola"));
    }

    @Test
    void stockIsDeductedInAscendingItemIdOrderWhateverTheRequestOrder() throws Exception {
        long raita = h.t.itemId("Raita");
        long chicken = h.t.itemId("Chicken Biryani");
        assertTrue(chicken < raita);
        submit(h.t.request(OrderSource.SWIGGY, "SW-4", "BIRYANI", "Raita", 1, "Chicken Biryani", 1));
        List<String> deducts = h.stockFaults.detailedSequence().stream().filter(s -> s.startsWith("deduct(")).toList();
        assertEquals(List.of("deduct(" + chicken + ",1)", "deduct(" + raita + ",1)"), deducts);
    }

    // ---- idempotency ----

    @Test
    void theSameReferenceTwiceIsADuplicateAndDeductsOnce() throws Exception {
        SubmissionResult first = submit(swiggy("SW-5"));
        SubmissionResult second = submit(swiggy("SW-5"));
        assertEquals(Outcome.DUPLICATE, second.outcome());
        assertEquals(first.orderId(), second.orderId());
        assertEquals(OrderStatus.ROUTED, second.existingStatus());
        assertFalse(second.compensationPending());
        assertEquals(1, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(48, h.t.stockOf("Chicken Biryani"), "no second deduction");
        assertEquals(1, h.metricsDelta().rejected());
        assertEquals(1, h.events.count(EventType.ORDER_DUPLICATE));
    }

    @Test
    void aFailedOrderKeepsItsReferenceConsumed() throws Exception {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        submit(swiggy("SW-6"));
        h.dispatcher.use(new FakeDispatchers.Accepting());
        SubmissionResult again = submit(swiggy("SW-6"));
        assertEquals(Outcome.DUPLICATE, again.outcome());
        assertEquals(OrderStatus.FAILED, again.existingStatus());
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
    }

    @RepeatedTest(20)
    void concurrentSubmissionsOfOneReferenceHaveExactlyOneWinner() throws Exception {
        h.reset();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "dup-race"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<SubmissionResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return submit(swiggy("RACE-1"));
                }));
            }
            go.countDown();
            int accepted = 0;
            int duplicates = 0;
            for (Future<SubmissionResult> f : futures) {
                SubmissionResult r = f.get(60, TimeUnit.SECONDS);
                if (r.outcome() == Outcome.ACCEPTED) {
                    accepted++;
                } else if (r.outcome() == Outcome.DUPLICATE) {
                    duplicates++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(threads - 1, duplicates);
            assertEquals(1, h.t.number("SELECT COUNT(*) FROM orders"));
            assertEquals(48, h.t.stockOf("Chicken Biryani"));
            assertEquals(0, h.inFlight.size(), "the in-flight guard is always released");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(20)
    void manyBuyersOfScarceStockSellExactlyWhatExists() throws Exception {
        h.reset();
        h.t.execute("UPDATE inventory SET stock_qty = 5 WHERE item_id = ?", h.t.itemId("Cola"));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "stock-buyers"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<SubmissionResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ref = "BUY-" + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return submit(h.t.request(OrderSource.ZOMATO, ref, "BURGER", "Cola", 1));
                }));
            }
            go.countDown();
            int accepted = 0;
            int outOfStock = 0;
            for (Future<SubmissionResult> f : futures) {
                SubmissionResult r = f.get(60, TimeUnit.SECONDS);
                if (r.outcome() == Outcome.ACCEPTED) {
                    accepted++;
                } else if (SubmissionResult.INSUFFICIENT_STOCK.equals(r.reasonCode())) {
                    outOfStock++;
                }
            }
            assertEquals(5, accepted, "N threads competing for K units sell exactly K");
            assertEquals(threads - 5, outOfStock);
            assertEquals(0, h.t.stockOf("Cola"), "never negative");
            assertEquals(5, h.t.number("SELECT COUNT(*) FROM orders"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(20)
    void ordersWithTheSameItemsInOppositeOrderNeverSurfaceADeadlock() throws Exception {
        h.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "opposite-order"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<SubmissionResult> one = pool.submit(() -> {
                go.await();
                return submit(h.t.request(OrderSource.SWIGGY, "OP-1", "BIRYANI", "Chicken Biryani", 1, "Raita", 1));
            });
            Future<SubmissionResult> two = pool.submit(() -> {
                go.await();
                return submit(h.t.request(OrderSource.ZOMATO, "OP-2", "BIRYANI", "Raita", 1, "Chicken Biryani", 1));
            });
            go.countDown();
            assertEquals(Outcome.ACCEPTED, one.get(60, TimeUnit.SECONDS).outcome());
            assertEquals(Outcome.ACCEPTED, two.get(60, TimeUnit.SECONDS).outcome());
            assertEquals(48, h.t.stockOf("Chicken Biryani"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    // ---- accepting flag ----

    @Test
    void notAcceptingIsStartingUpBeforeTheFirstOpenAndShuttingDownAfterwards() throws Exception {
        ServiceHarness fresh = ServiceHarness.create();
        try {
            fresh.service.setAccepting(false);
            SubmissionResult shutting = fresh.service.submitOrder(fresh.t.request(OrderSource.SWIGGY, "N-1", "BIRYANI", "Raita", 1));
            assertEquals(SubmissionResult.SHUTTING_DOWN, shutting.reasonCode(), "it was open once (the harness opens it)");
        } finally {
            fresh.close();
        }
        h.service.setAccepting(false);
        assertEquals(SubmissionResult.SHUTTING_DOWN, submit(swiggy("N-2")).reasonCode());
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"));
        assertFalse(h.service.isAccepting());
        h.service.setAccepting(true);
        assertEquals(Outcome.ACCEPTED, submit(swiggy("N-3")).outcome());
    }

    // ---- queue-full compensation and the post-offer rule ----

    @Test
    void whenTheQueueRefusesTheOrderIsFailedAndItsStockRestoredInOneTransaction() throws Exception {
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        SubmissionResult r = submit(swiggy("QF-1"));
        assertEquals(Outcome.QUEUE_FULL, r.outcome());
        assertTrue(r.compensated());
        Order order = load(r.orderId());
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals(List.of(OrderStatus.PLACED, OrderStatus.VALIDATED, OrderStatus.FAILED), edges(r.orderId()));
        StatusHistoryEntry last = order.getHistory().get(2);
        assertEquals("SYSTEM", last.actor());
        assertEquals("QUEUE_FULL", last.note());
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(50, h.t.stockOf("Raita"));
        assertEquals("0", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", r.orderId()));
        assertEquals(1, h.metricsDelta().failed());
        assertEquals(1, h.events.count(EventType.QUEUE_FULL));
        assertStockLedger();
    }

    @Test
    void anErrorAfterASuccessfulOfferNeverFailsTheOrder() throws Exception {
        h.orderFaults.failBefore("transition", 2, new SQLTransientConnectionException("blip")); // the ROUTED update
        SubmissionResult r = submit(swiggy("PO-1"));
        assertEquals(Outcome.ACCEPTED, r.outcome(), "RR-1: queued means accepted");
        assertEquals(OrderStatus.VALIDATED, load(r.orderId()).getStatus(), "the worker routes it itself (RR-3)");
        assertEquals(48, h.t.stockOf("Chicken Biryani"), "stock stays deducted: no compensation after a successful offer");
        assertEquals(0, h.metricsDelta().failed());
    }

    @Test
    void aTemporaryDatabaseOutageDuringTheOrderTransactionIsATemporaryFailureWithNothingLeftBehind() throws Exception {
        h.orderFaults.failAfter("insertItems", 1, new SQLTransientConnectionException("down"));
        SubmissionResult r = submit(swiggy("OUT-1"));
        assertEquals(SubmissionResult.TEMPORARY_FAILURE, r.reasonCode());
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(750, h.t.number("SELECT SUM(stock_qty) FROM inventory"));
        assertEquals(0, h.inFlight.size());
    }

    // ---- transitions, cancel and fail: the restore matrix (FR-INV-06, FR-LIF-05) ----

    private long accepted(String ref) throws Exception {
        return submit(swiggy(ref)).orderId();
    }

    private long inStatus(String ref, OrderStatus... path) throws Exception {
        long id = accepted(ref);
        OrderStatus current = OrderStatus.ROUTED;
        for (OrderStatus next : path) {
            assertTrue(h.service.transition(id, current, next, "test", null));
            current = next;
        }
        return id;
    }

    @Test
    void cancelFromValidatedOrRoutedRestoresStockExactlyOnce() throws Exception {
        long routed = accepted("CX-1");
        assertEquals(CancelOutcome.CANCELLED, h.service.cancelOrderDetailed(routed, "staff"));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals("0", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", routed));
        assertEquals(CancelOutcome.NOT_CANCELLABLE, h.service.cancelOrderDetailed(routed, "staff"), "terminal: nothing more happens");
        assertFalse(h.service.transition(routed, OrderStatus.ROUTED, OrderStatus.CANCELLED, "x", null), "a stale expected status changes nothing");
        assertEquals(50, h.t.stockOf("Chicken Biryani"), "restored once, not twice");

        // a VALIDATED order (never queued) is cancellable too and restores its stock
        long validated = h.t.persistAt(h.t.create(swiggy("CX-2")), OrderStatus.VALIDATED);
        h.t.execute("UPDATE inventory SET stock_qty = stock_qty - 2 WHERE item_id = ?", h.t.itemId("Chicken Biryani"));
        h.t.execute("UPDATE inventory SET stock_qty = stock_qty - 1 WHERE item_id = ?", h.t.itemId("Raita"));
        h.t.execute("UPDATE orders SET stock_deducted = 1 WHERE id = ?", validated);
        assertTrue(h.service.cancelOrder(validated, "staff"));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(50, h.t.stockOf("Raita"));
        assertEquals(2, h.events.count(EventType.ORDER_CANCELLED));
    }

    @Test
    void failFromRoutedRestoresButFailFromPreparingOrLaterDoesNot() throws Exception {
        long routed = accepted("FX-1");
        assertTrue(h.service.failOrder(routed, OrderStatus.ROUTED, "worker", "PROCESSING_ERROR"));
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals("0", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", routed));

        long preparing = inStatus("FX-2", OrderStatus.PREPARING);
        assertEquals(48, h.t.stockOf("Chicken Biryani"));
        assertTrue(h.service.failOrder(preparing, OrderStatus.PREPARING, "worker", "PROCESSING_ERROR"));
        assertEquals(48, h.t.stockOf("Chicken Biryani"), "stock is consumed once the order is being prepared");
        assertEquals("1", h.t.text("SELECT stock_deducted FROM orders WHERE id = ?", preparing));

        long delivering = inStatus("FX-3", OrderStatus.PREPARING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY);
        assertTrue(h.service.failOrder(delivering, OrderStatus.OUT_FOR_DELIVERY, "worker", "PROCESSING_ERROR"));
        assertEquals(46, h.t.stockOf("Chicken Biryani"), "FX-2 and FX-3 each consumed 2 and neither was restored");
        assertStockLedger();
    }

    @Test
    void cancelIsRefusedOnceTheOrderIsBeingPrepared() throws Exception {
        long preparing = inStatus("CP-1", OrderStatus.PREPARING);
        assertEquals(CancelOutcome.NOT_CANCELLABLE, h.service.cancelOrderDetailed(preparing, "staff"));
        assertFalse(h.service.cancelOrder(preparing, "staff"));
        assertEquals(OrderStatus.PREPARING, load(preparing).getStatus());
        assertEquals(CancelOutcome.NOT_FOUND, h.service.cancelOrderDetailed(31337L, "staff"));
    }

    @Test
    void aCancelThatLosesTheRaceAgainstTheRoutedUpdateRetriesAndSucceeds() throws Exception {
        h.dispatcher.use(new FakeDispatchers.Accepting());
        // Create a VALIDATED order directly, then let "the router" move it to ROUTED between the cancel's read and its update.
        long id = h.t.persistAt(h.t.create(swiggy("RC-1")), OrderStatus.VALIDATED);
        h.t.execute("UPDATE inventory SET stock_qty = stock_qty - 2 WHERE item_id = ?", h.t.itemId("Chicken Biryani"));
        h.t.execute("UPDATE inventory SET stock_qty = stock_qty - 1 WHERE item_id = ?", h.t.itemId("Raita"));
        h.t.execute("UPDATE orders SET stock_deducted = 1 WHERE id = ?", id);
        h.orderFaults.beforeCall("transition", 1, () -> {
            try {
                h.t.execute("UPDATE orders SET status = 'ROUTED' WHERE id = ?", id);
                h.t.execute("INSERT INTO order_status_history (order_id, from_status, to_status, changed_at, actor) VALUES (?, 'VALIDATED', 'ROUTED', ?, 'router')",
                        id, java.time.LocalDateTime.ofInstant(h.clock.instant(), java.time.ZoneOffset.UTC));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(CancelOutcome.CANCELLED, h.service.cancelOrderDetailed(id, "staff"), "RR-7: re-read and retry, not a false refusal");
        assertEquals(OrderStatus.CANCELLED, load(id).getStatus());
        assertEquals(50, h.t.stockOf("Chicken Biryani"));
        assertEquals(2, h.orderFaults.calls("findState"), "it read the status twice");
    }

    @Test
    void anIllegalEdgeIsRefusedBeforeTheDatabaseIsTouched() throws Exception {
        long id = accepted("IL-1");
        h.orderFaults.resetCounters();
        h.historyFaults.resetCounters();
        assertThrows(InvalidStatusTransitionException.class, () -> h.service.transition(id, OrderStatus.ROUTED, OrderStatus.DELIVERED, "x", null));
        assertThrows(InvalidStatusTransitionException.class, () -> h.service.transition(id, OrderStatus.DELIVERED, OrderStatus.ROUTED, "x", null));
        assertThrows(InvalidStatusTransitionException.class, () -> h.service.failOrder(id, OrderStatus.CANCELLED, "x", "r"));
        assertEquals(List.of(), h.orderFaults.sequence());
        assertEquals(List.of(), h.historyFaults.sequence());
        assertEquals(OrderStatus.ROUTED, load(id).getStatus());
    }

    @RepeatedTest(20)
    void twoThreadsMakingTheSameTransitionHaveExactlyOneWinnerAndOneHistoryRow() throws Exception {
        h.reset();
        long id = accepted("TT-1");
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "transition-race"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    if (h.service.transition(id, OrderStatus.ROUTED, OrderStatus.PREPARING, "worker", null)) {
                        wins.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            assertEquals(1, wins.get());
            assertEquals(1, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'PREPARING'", id));
            assertEquals(1, h.events.count(EventType.ORDER_PREPARING));
            com.cloudkitchen.testsupport.TestSupport.assertEachEdgeOnce(h.t, id);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(20)
    void twoConcurrentCancelsRestoreStockExactlyOnce() throws Exception {
        h.reset();
        long id = accepted("TC-1");
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "cancel-race"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return h.service.cancelOrder(id, "staff");
                }));
            }
            go.countDown();
            int cancelled = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(60, TimeUnit.SECONDS)) {
                    cancelled++;
                }
            }
            assertEquals(1, cancelled);
            assertEquals(50, h.t.stockOf("Chicken Biryani"), "restored once");
            assertEquals(50, h.t.stockOf("Raita"));
            assertStockLedger();
            com.cloudkitchen.testsupport.TestSupport.assertEachEdgeOnce(h.t, id);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
