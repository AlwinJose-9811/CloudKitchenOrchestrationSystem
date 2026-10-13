package com.cloudkitchen.dao.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderDaoTest {

    private static final Duration TTL = DbTestSupport.TTL;

    private FakeClock clock;
    private DbFixture db;
    private DbTestSupport t;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
        t = new DbTestSupport(db);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
        clock.set(Instant.parse("2026-10-10T12:00:00Z"));
    }

    private <T> T tx(com.cloudkitchen.db.TxWork<T> work) throws Exception {
        return db.database().inTransaction(work);
    }

    private boolean ok(com.cloudkitchen.db.TxWork<Boolean> work) throws Exception {
        return Boolean.TRUE.equals(db.database().inTransaction(work));
    }

    private long num(com.cloudkitchen.db.TxWork<? extends Number> work) throws Exception {
        return db.database().inTransaction(work).longValue();
    }

    private boolean step(long id, OrderStatus from, OrderStatus to) throws Exception {
        return tx(c -> t.orders.transition(c, id, from, to, clock.instant()));
    }

    // ---- insert, reconstruction, constraints ----

    @Test
    void anOrderIsReconstructedExactlyWithItemsAndHistory() throws Exception {
        Order order = t.biryaniOrder("SW-100");
        long id = t.persist(order);
        Order loaded = tx(c -> t.orders.findById(c, id)).orElseThrow();
        assertEquals(id, loaded.getId());
        assertEquals(order, loaded, "equality is (source, externalRef)");
        assertEquals(OrderStatus.PLACED, loaded.getStatus());
        assertEquals(order.getTrackingCode(), loaded.getTrackingCode());
        assertEquals("BIRYANI", loaded.getBrandCode());
        assertEquals("Asha Rao", loaded.getCustomerName());
        assertEquals(order.getDeliveryDistanceKm(), loaded.getDeliveryDistanceKm());
        assertEquals(order.getPaymentMode(), loaded.getPaymentMode());
        assertEquals(order.getCreatedAt(), loaded.getCreatedAt());
        assertEquals(order.getEstimatedDeliveryAt(), loaded.getEstimatedDeliveryAt());
        assertEquals(order.getSubtotal(), loaded.getSubtotal());
        assertEquals("547.00", loaded.getSubtotal().toPlainString());
        assertEquals(2, loaded.getItems().size());
        assertEquals(1, loaded.getHistory().size());
        assertEquals(order.processPayment(), loaded.processPayment());
        List<OrderItem> items = tx(c -> t.orders.findItems(c, id));
        assertTrue(items.get(0).getItemId() < items.get(1).getItemId(), "ascending item_id");
    }

    @Test
    void paymentColumnsComeFromProcessPayment() throws Exception {
        long id = t.persist(t.biryaniOrder("SW-101"));
        assertEquals("547.00", t.text("SELECT total_amount FROM orders WHERE id = ?", id));
        assertEquals("109.40", t.text("SELECT platform_fee FROM orders WHERE id = ?", id), "20% of 547.00");
        assertEquals("PAID", t.text("SELECT payment_status FROM orders WHERE id = ?", id));
        assertEquals("0", t.text("SELECT stock_deducted FROM orders WHERE id = ?", id));
    }

    @Test
    void theSameSourceAndReferenceIsRejectedByTheUniqueConstraint() throws Exception {
        t.persist(t.biryaniOrder("DUP-2"));
        Order again = t.biryaniOrder("DUP-2");
        com.cloudkitchen.exception.PersistenceException e = assertThrows(com.cloudkitchen.exception.PersistenceException.class,
                () -> tx(c -> t.orders.insert(c, again)));
        assertTrue(SqlErrors.isDuplicateKey(e));
        assertEquals(1, t.number("SELECT COUNT(*) FROM orders WHERE external_ref = 'DUP-2'"));
    }

    @Test
    void referencesAreUniquePerSourceAndCaseSensitive() throws Exception {
        t.persist(t.create(t.request(OrderSource.SWIGGY, "abc", "BIRYANI", "Chicken Biryani", 1)));
        t.persist(t.create(t.request(OrderSource.ZOMATO, "abc", "BIRYANI", "Chicken Biryani", 1)));
        t.persist(t.create(t.request(OrderSource.SWIGGY, "ABC", "BIRYANI", "Chicken Biryani", 1)));
        assertEquals(3, t.number("SELECT COUNT(*) FROM orders"));
        assertTrue(tx(c -> t.orders.findIdBySourceAndRef(c, OrderSource.SWIGGY, "abc")).isPresent());
        assertTrue(tx(c -> t.orders.findIdBySourceAndRef(c, OrderSource.IN_HOUSE, "abc")).isEmpty());
    }

    @Test
    void foreignKeysAndChecksAreEnforcedByTheDatabase() throws Exception {
        Order order = t.biryaniOrder("FK-1");
        com.cloudkitchen.exception.PersistenceException orphanItems = assertThrows(com.cloudkitchen.exception.PersistenceException.class,
                () -> tx(c -> {
                    t.orders.insertItems(c, 999_999L, order.getItems());
                    return null;
                }));
        assertEquals("23000", ((SQLException) orphanItems.getCause()).getSQLState());
        long id = t.persist(t.biryaniOrder("FK-2"));
        assertThrows(SQLException.class, () -> t.execute("UPDATE orders SET status = 'BOGUS' WHERE id = ?", id));
        assertThrows(SQLException.class, () -> t.execute("UPDATE orders SET station_id = 999999 WHERE id = ?", id));
        assertThrows(SQLException.class, () -> t.execute("UPDATE orders SET total_amount = -1 WHERE id = ?", id));
        assertThrows(SQLException.class, () -> t.execute("UPDATE order_items SET quantity = 21 WHERE order_id = ?", id));
        assertThrows(SQLException.class, () -> t.execute("UPDATE orders SET compensation_state = 'MAYBE' WHERE id = ?", id));
    }

    @Test
    void utf8mb4NamesAndExactDecimalsSurviveAndTimestampsAreStoredInUtc() throws Exception {
        Order order = t.create(new com.cloudkitchen.dto.OrderRequest(OrderSource.IN_HOUSE, "U-1", "BURGER", "नमस्ते Rao 🍔",
                new BigDecimal("0.01"), com.cloudkitchen.domain.enums.PaymentMode.UPI,
                List.of(new com.cloudkitchen.dto.OrderItemRequest(t.itemId("Cola"), 3))));
        long id = t.persist(order);
        Order loaded = tx(c -> t.orders.findById(c, id)).orElseThrow();
        assertEquals("नमस्ते Rao 🍔", loaded.getCustomerName());
        assertEquals("0.01", loaded.getDeliveryDistanceKm().toPlainString());
        assertEquals("177.00", loaded.getSubtotal().toPlainString());
        assertEquals("2026-10-10 12:00:00.000", t.text("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM orders WHERE id = ?", id).substring(0, 23),
                "stored as UTC regardless of the JVM time zone");
    }

    // ---- conditional transitions ----

    @Test
    void aTransitionReportsWhetherThisCallerChangedTheRow() throws Exception {
        long id = t.persist(t.biryaniOrder("T-1"));
        assertTrue(step(id, OrderStatus.PLACED, OrderStatus.VALIDATED));
        assertFalse(step(id, OrderStatus.PLACED, OrderStatus.VALIDATED), "already moved: a stale expected status changes nothing");
        assertFalse(step(id, OrderStatus.PLACED, OrderStatus.CANCELLED), "stale status must not overwrite the newer state");
        assertEquals("VALIDATED", t.text("SELECT status FROM orders WHERE id = ?", id));
        assertFalse(step(424242L, OrderStatus.PLACED, OrderStatus.VALIDATED), "unknown order");
    }

    @Test
    void stockFlagsAreClaimedExactlyOnce() throws Exception {
        long id = t.persist(t.biryaniOrder("S-1"));
        assertTrue(ok(c -> t.orders.markStockDeducted(c, id)));
        assertFalse(ok(c -> t.orders.markStockDeducted(c, id)));
        assertTrue(ok(c -> t.orders.claimStockRestore(c, id)));
        assertFalse(ok(c -> t.orders.claimStockRestore(c, id)));
    }

    @Test
    void routingIsRefusedWhileCompensationIsPendingAndAnExitResolvesTheMarker() throws Exception {
        long id = t.persistAt(t.biryaniOrder("C-1"), OrderStatus.VALIDATED);
        assertTrue(ok(c -> t.orders.markCompensationPending(c, id, "QUEUE_FULL", clock.instant().plusSeconds(30))));
        assertFalse(ok(c -> t.orders.markCompensationPending(c, id, "QUEUE_FULL", null)), "already marked");
        assertFalse(step(id, OrderStatus.VALIDATED, OrderStatus.ROUTED), "a PENDING order is never routed (guard 2)");
        assertEquals(1, tx(c -> t.orders.findAllPendingCompensations(c)).size());
        assertTrue(step(id, OrderStatus.VALIDATED, OrderStatus.FAILED));
        assertEquals("RESOLVED", t.text("SELECT compensation_state FROM orders WHERE id = ?", id));
        assertEquals(0, tx(c -> t.orders.findAllPendingCompensations(c)).size());
    }

    @Test
    void markCompensationPendingNeedsAValidatedOrder() throws Exception {
        long placed = t.persist(t.biryaniOrder("C-2"));
        assertFalse(ok(c -> t.orders.markCompensationPending(c, placed, "QUEUE_FULL", null)));
        long routed = t.persistAt(t.biryaniOrder("C-3"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        assertFalse(ok(c -> t.orders.markCompensationPending(c, routed, "QUEUE_FULL", null)));
    }

    @Test
    void compensationBookkeepingAndDueSelection() throws Exception {
        long id = t.persistAt(t.biryaniOrder("C-4"), OrderStatus.VALIDATED);
        Instant later = clock.instant().plusSeconds(60);
        tx(c -> t.orders.markCompensationPending(c, id, "INTERRUPTED", later));
        assertEquals(0, tx(c -> t.orders.findDuePendingCompensations(c, 10)).size(), "not yet due");
        clock.advance(Duration.ofSeconds(61));
        assertEquals(1, tx(c -> t.orders.findDuePendingCompensations(c, 10)).size());
        tx(c -> {
            t.orders.recordCompensationFailure(c, id, clock.instant().plusSeconds(600));
            return null;
        });
        assertEquals("2", t.text("SELECT compensation_attempts FROM orders WHERE id = ?", id));
        assertEquals(0, tx(c -> t.orders.findDuePendingCompensations(c, 10)).size());
        assertEquals("INTERRUPTED", t.text("SELECT compensation_reason FROM orders WHERE id = ?", id));
    }

    // ---- leases and fencing ----

    private OptionalInt acquire(long id, String owner) throws Exception {
        return tx(c -> t.orders.acquireLease(c, id, owner, TTL));
    }

    @Test
    void aLeaseHasOneOwnerAtATimeAndTheEpochOnlyGrows() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-1"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        assertEquals(1, acquire(id, "BIRYANI#1").getAsInt());
        assertTrue(acquire(id, "BIRYANI#2").isEmpty(), "valid lease of another owner");
        assertEquals(2, acquire(id, "BIRYANI#1").getAsInt(), "the same owner string may re-acquire after a lost reply");
        clock.advance(TTL.plusMillis(1));
        assertEquals(3, acquire(id, "BIRYANI#2").getAsInt(), "free after expiry: takeover with a new epoch");
    }

    @Test
    void noLeaseOnTerminalOrPendingCompensationOrders() throws Exception {
        long done = t.persistAt(t.biryaniOrder("L-2"), OrderStatus.CANCELLED);
        assertTrue(acquire(done, "BIRYANI#1").isEmpty());
        long pending = t.persistAt(t.biryaniOrder("L-3"), OrderStatus.VALIDATED);
        tx(c -> t.orders.markCompensationPending(c, pending, "QUEUE_FULL", null));
        assertTrue(acquire(pending, "BIRYANI#1").isEmpty(), "guard 1: a pending order cannot be leased");
        assertTrue(acquire(777_777L, "BIRYANI#1").isEmpty());
    }

    @Test
    void renewalNeverRevivesAnExpiredOrClearedOrSupersededLease() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-4"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        int epoch = acquire(id, "BIRYANI#1").getAsInt();
        clock.advance(Duration.ofSeconds(10));
        assertTrue(ok(c -> t.orders.renewLease(c, id, epoch, "BIRYANI#1", TTL)));
        clock.advance(TTL.minusMillis(150)); // inside the 200 ms dead zone before expiry
        assertFalse(ok(c -> t.orders.renewLease(c, id, epoch, "BIRYANI#1", TTL)), "within the margin: not usable");
        clock.advance(Duration.ofSeconds(1));
        assertFalse(ok(c -> t.orders.renewLease(c, id, epoch, "BIRYANI#1", TTL)), "expired: no revival");
        int second = acquire(id, "BIRYANI#2").getAsInt();
        assertFalse(ok(c -> t.orders.renewLease(c, id, epoch, "BIRYANI#1", TTL)), "superseded epoch/owner");
        assertTrue(ok(c -> t.orders.releaseLease(c, id, second, "BIRYANI#2")));
        assertFalse(ok(c -> t.orders.renewLease(c, id, second, "BIRYANI#2", TTL)), "cleared lease never matches (NULL)");
    }

    @Test
    void releaseNeedsTheExactEpochAndOwner() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-5"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        int epoch = acquire(id, "BIRYANI#1").getAsInt();
        assertFalse(ok(c -> t.orders.releaseLease(c, id, epoch + 1, "BIRYANI#1")));
        assertFalse(ok(c -> t.orders.releaseLease(c, id, epoch, "BIRYANI#9")));
        assertTrue(ok(c -> t.orders.releaseLease(c, id, epoch, "BIRYANI#1")));
        assertEquals(epoch, tx(c -> t.orders.findState(c, id)).orElseThrow().claimEpoch(), "release keeps the epoch");
        assertEquals(epoch + 1, acquire(id, "BIRYANI#2").getAsInt());
    }

    @Test
    void aFencedStepNeedsEpochOwnerValidLeaseAndStatus() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-6"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        int epoch = acquire(id, "BIRYANI#1").getAsInt();
        FencingToken good = new FencingToken(id, "BIRYANI#1", epoch);
        assertFalse(ok(c -> t.orders.transition(c, id, OrderStatus.ROUTED, OrderStatus.PREPARING, new FencingToken(id, "BIRYANI#1", epoch + 1), TTL)), "stale epoch");
        assertFalse(ok(c -> t.orders.transition(c, id, OrderStatus.ROUTED, OrderStatus.PREPARING, new FencingToken(id, "BIRYANI#2", epoch), TTL)), "wrong owner");
        assertFalse(ok(c -> t.orders.transition(c, id, OrderStatus.VALIDATED, OrderStatus.ROUTED, good, TTL)), "status mismatch");
        assertTrue(ok(c -> t.orders.transition(c, id, OrderStatus.ROUTED, OrderStatus.PREPARING, good, TTL)));
        assertFalse(ok(c -> t.orders.transition(c, id, OrderStatus.ROUTED, OrderStatus.PREPARING, good, TTL)), "repeat");
        clock.advance(TTL.plusSeconds(1));
        assertFalse(ok(c -> t.orders.transition(c, id, OrderStatus.PREPARING, OrderStatus.PACKED, good, TTL)), "expired lease");
        assertEquals("PREPARING", t.text("SELECT status FROM orders WHERE id = ?", id));
    }

    @Test
    void aFencedTerminalStepClearsTheLeaseAndTheOwnerCannotActAgain() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-7"), OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY);
        FencingToken token = new FencingToken(id, "BIRYANI#1", acquire(id, "BIRYANI#1").getAsInt());
        assertTrue(ok(c -> t.orders.transition(c, id, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, token, TTL)));
        assertEquals(null, t.text("SELECT claimed_by FROM orders WHERE id = ?", id));
        assertEquals(null, t.text("SELECT lease_expires_at FROM orders WHERE id = ?", id));
        assertFalse(ok(c -> t.orders.renewLease(c, id, token.epoch(), "BIRYANI#1", TTL)));
    }

    @Test
    void aFencedRoutedStepIsRefusedForAPendingOrder() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-8"), OrderStatus.VALIDATED);
        FencingToken token = new FencingToken(id, "BIRYANI#1", acquire(id, "BIRYANI#1").getAsInt());
        tx(c -> t.orders.markCompensationPending(c, id, "QUEUE_FULL", null));
        assertFalse(ok(c -> t.orders.transition(c, id, OrderStatus.VALIDATED, OrderStatus.ROUTED, token, TTL)));
    }

    @Test
    void anUnfencedTerminalTransitionClearsAnyLease() throws Exception {
        long id = t.persistAt(t.biryaniOrder("L-9"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        acquire(id, "BIRYANI#1");
        assertTrue(step(id, OrderStatus.ROUTED, OrderStatus.CANCELLED));
        assertEquals(null, t.text("SELECT claimed_by FROM orders WHERE id = ?", id));
    }

    @Test
    void expiringAnOwnersLeasesMatchesTheExactOwnerStringAndStartupClearsAllButKeepsEpochs() throws Exception {
        long a = t.persistAt(t.biryaniOrder("E-1"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        long b = t.persistAt(t.biryaniOrder("E-2"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        long c1 = t.persistAt(t.biryaniOrder("E-3"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        acquire(a, "BIRYANI#1");
        acquire(b, "BIRYANI#10");
        acquire(c1, "BIRYANI#1");
        assertEquals(List.of(a, c1), tx(c -> t.orders.expireLeasesOf(c, "BIRYANI#1")), "BIRYANI#10 is a different generation");
        assertEquals("BIRYANI#10", t.text("SELECT claimed_by FROM orders WHERE id = ?", b));
        assertEquals(1, num(c -> t.orders.clearAllLeases(c)));
        assertEquals(1, tx(c -> t.orders.findState(c, b)).orElseThrow().claimEpoch(), "the epoch is never reset");
    }

    // ---- selection queries ----

    @Test
    void nonTerminalSelectionsExcludeTerminalAndPendingAsDesigned() throws Exception {
        long placed = t.persist(t.biryaniOrder("Q-1"));
        long routed = t.persistAt(t.biryaniOrder("Q-2"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        long pending = t.persistAt(t.biryaniOrder("Q-3"), OrderStatus.VALIDATED);
        tx(c -> t.orders.markCompensationPending(c, pending, "QUEUE_FULL", null));
        t.persistAt(t.biryaniOrder("Q-4"), OrderStatus.CANCELLED);
        t.persistAt(t.biryaniOrder("Q-5"), OrderStatus.VALIDATED, OrderStatus.FAILED);
        assertEquals(List.of(placed, routed, pending), tx(c -> t.orders.findNonTerminal(c)).stream().map(Order::getId).toList());
        assertEquals(List.of(placed, routed), tx(c -> t.orders.findNonTerminalForProcessing(c)).stream().map(Order::getId).toList());
    }

    @Test
    void theSweeperQuerySkipsRecentLeasedAndPendingOrders() throws Exception {
        long old = t.persistAt(t.biryaniOrder("W-1"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        long leased = t.persistAt(t.biryaniOrder("W-2"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        long pending = t.persistAt(t.biryaniOrder("W-3"), OrderStatus.VALIDATED);
        tx(c -> t.orders.markCompensationPending(c, pending, "QUEUE_FULL", null));
        acquire(leased, "BIRYANI#1");
        assertEquals(0, tx(c -> t.orders.findStaleNonTerminal(c, Duration.ofSeconds(120), 10)).size(), "nothing is old yet");
        clock.advance(Duration.ofSeconds(121));
        acquire(leased, "BIRYANI#1"); // lease renewed from the new time
        assertEquals(List.of(old), tx(c -> t.orders.findStaleNonTerminal(c, Duration.ofSeconds(120), 10)).stream().map(Order::getId).toList());
    }

    @Test
    void reconcileCandidatesAreOrdersThatShouldBeInAQueue() throws Exception {
        long station = t.station("BIRYANI").getId();
        long routed = t.persistAt(t.biryaniOrder("R-1"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        long freshValidated = t.persistAt(t.biryaniOrder("R-2"), OrderStatus.VALIDATED);
        long leased = t.persistAt(t.biryaniOrder("R-3"), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        acquire(leased, "BIRYANI#1");
        long otherStation = t.persistAt(t.create(t.request(OrderSource.SWIGGY, "R-4", "BURGER", "Cola", 1)), OrderStatus.VALIDATED, OrderStatus.ROUTED);
        Duration window = Duration.ofSeconds(30);
        assertEquals(List.of(routed), tx(c -> t.orders.findReconcilable(c, station, window, 50)).stream().map(Order::getId).toList());
        clock.advance(Duration.ofSeconds(31));
        acquire(leased, "BIRYANI#1"); // the first lease has expired by now; take a new one
        assertEquals(List.of(routed, freshValidated), tx(c -> t.orders.findReconcilable(c, station, window, 50)).stream().map(Order::getId).toList(),
                "a VALIDATED order older than the dispatch window is a candidate; a leased one is not");
        assertTrue(otherStation > 0);
    }

    @Test
    void countsSummariesFiltersAndPaging() throws Exception {
        for (int i = 1; i <= 5; i++) {
            t.persistAt(t.create(t.request(OrderSource.SWIGGY, "SW_" + i, "BIRYANI", "Raita", 1)), OrderStatus.VALIDATED);
        }
        t.persistAt(t.create(t.request(OrderSource.ZOMATO, "ZO-1", "PIZZA", "Brownie", 2)), OrderStatus.CANCELLED);
        t.persistAt(t.create(t.request(OrderSource.SWIGGY, "SWA1", "BIRYANI", "Raita", 1)), OrderStatus.VALIDATED);
        Map<OrderStatus, Long> counts = tx(c -> t.orders.countByStatus(c));
        assertEquals(9, counts.size(), "every status is present, with zero when none");
        assertEquals(6L, counts.get(OrderStatus.VALIDATED).longValue());
        assertEquals(1L, counts.get(OrderStatus.CANCELLED).longValue());
        assertEquals(0L, counts.get(OrderStatus.DELIVERED).longValue());
        assertEquals(7, num(c -> t.orders.count(c, OrderFilter.any())));
        assertEquals(6, num(c -> t.orders.count(c, new OrderFilter(OrderStatus.VALIDATED, null, null, null, null))));
        assertEquals(1, num(c -> t.orders.count(c, new OrderFilter(null, OrderSource.ZOMATO, null, null, null))));
        assertEquals(0, num(c -> t.orders.count(c, new OrderFilter(null, null, "%", null, null))), "a literal percent sign, not a wildcard");
        assertEquals(5, num(c -> t.orders.count(c, new OrderFilter(null, null, "SW_", null, null))), "an underscore is a literal too: SWA1 would match a wildcard but not SW_");
        assertEquals(0, num(c -> t.orders.count(c, new OrderFilter(null, null, "SWX", null, null))));
        List<OrderSummary> firstPage = tx(c -> t.orders.findSummaries(c, OrderFilter.any(), 4, 0));
        List<OrderSummary> secondPage = tx(c -> t.orders.findSummaries(c, OrderFilter.any(), 4, 4));
        assertEquals(4, firstPage.size());
        assertEquals(3, secondPage.size());
        assertTrue(firstPage.get(0).id() > firstPage.get(3).id(), "newest first");
        assertEquals(0, tx(c -> t.orders.findSummaries(c, new OrderFilter(null, null, "no-such", null, null), 10, 0)).size());
        Instant now = clock.instant();
        assertEquals(7, num(c -> t.orders.count(c, new OrderFilter(null, null, null, now.minusSeconds(1), now.plusSeconds(1)))));
        assertEquals(0, num(c -> t.orders.count(c, new OrderFilter(null, null, null, now.plusSeconds(1), null))));
    }

    @Test
    void injectionAttemptsInFilterTextAreJustData() throws Exception {
        t.persist(t.biryaniOrder("INJ-1"));
        assertEquals(0, num(c -> t.orders.count(c, new OrderFilter(null, null, "x' OR '1'='1", null, null))));
        assertEquals(1, t.number("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void lookupByTrackingCodeAndTheDailyReport() throws Exception {
        Order order = t.biryaniOrder("TR-1");
        long id = t.persist(order);
        assertEquals(id, tx(c -> t.orders.findByTrackingCode(c, order.getTrackingCode())).orElseThrow().getId());
        assertTrue(tx(c -> t.orders.findByTrackingCode(c, "ZZZZZZZZZZZZ")).isEmpty());
        Instant day = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        var rows = tx(c -> t.orders.findForReport(c, day, day.plus(Duration.ofDays(1))));
        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).itemCount());
        assertEquals("547.00", rows.get(0).total().toPlainString());
        assertEquals("109.40", rows.get(0).platformFee().toPlainString());
        assertEquals(0, tx(c -> t.orders.findForReport(c, day.plus(Duration.ofDays(1)), day.plus(Duration.ofDays(2)))).size());
    }

    @Test
    void aRecoveredOrderIsInsertedWithoutStockAndItsStatusIsKept() throws Exception {
        Order original = t.biryaniOrder("REC-1");
        Order recovered = com.cloudkitchen.domain.OrderFactory.rehydrate(null, original.getSource(), original.getExternalRef(),
                original.getTrackingCode(), original.getStationId(), original.getBrandCode(), OrderStatus.PLACED,
                original.getCustomerName(), original.getDeliveryDistanceKm(), original.getPaymentMode(), original.getItems(),
                List.of(), original.getCreatedAt());
        long id = db.database().inTransaction(c -> {
            long newId = t.orders.insertRecovered(c, recovered);
            t.orders.insertItems(c, newId, recovered.getItems());
            return newId;
        });
        assertNotNull(tx(c -> t.orders.findById(c, id)).orElseThrow());
        assertEquals("0", t.text("SELECT stock_deducted FROM orders WHERE id = ?", id));
        assertEquals(50, t.stockOf("Chicken Biryani"));
    }

    @Test
    void anUnknownOrderIsEmptyNotAnError() throws Exception {
        assertTrue(tx(c -> t.orders.findById(c, 31337L)).isEmpty());
        assertTrue(tx(c -> t.orders.findState(c, 31337L)).isEmpty());
        assertEquals(List.of(), tx(c -> t.orders.findItems(c, 31337L)));
    }
}
