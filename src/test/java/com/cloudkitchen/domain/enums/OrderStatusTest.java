package com.cloudkitchen.domain.enums;

import static com.cloudkitchen.domain.enums.OrderStatus.CANCELLED;
import static com.cloudkitchen.domain.enums.OrderStatus.DELIVERED;
import static com.cloudkitchen.domain.enums.OrderStatus.FAILED;
import static com.cloudkitchen.domain.enums.OrderStatus.OUT_FOR_DELIVERY;
import static com.cloudkitchen.domain.enums.OrderStatus.PACKED;
import static com.cloudkitchen.domain.enums.OrderStatus.PLACED;
import static com.cloudkitchen.domain.enums.OrderStatus.PREPARING;
import static com.cloudkitchen.domain.enums.OrderStatus.ROUTED;
import static com.cloudkitchen.domain.enums.OrderStatus.VALIDATED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.InvalidStatusTransitionException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks all 9 x 9 = 81 (from, to) pairs against a table written out independently of the implementation, so the test
 * fails if the enum's table is changed by mistake. The table is the locked lifecycle of CLAUDE.md section 3.
 */
class OrderStatusTest {

    /** The locked table, spelled out edge by edge. */
    private static final Set<String> ALLOWED = Set.of(
            "PLACED>VALIDATED", "PLACED>FAILED", "PLACED>CANCELLED",
            "VALIDATED>ROUTED", "VALIDATED>CANCELLED", "VALIDATED>FAILED",
            "ROUTED>PREPARING", "ROUTED>CANCELLED", "ROUTED>FAILED",
            "PREPARING>PACKED", "PREPARING>FAILED",
            "PACKED>OUT_FOR_DELIVERY",
            "OUT_FOR_DELIVERY>DELIVERED", "OUT_FOR_DELIVERY>FAILED");

    /** The only stock-restoring edges: from a status that holds stock to a status that gives it up. */
    private static final Set<String> RESTORING = Set.of(
            "VALIDATED>CANCELLED", "VALIDATED>FAILED", "ROUTED>CANCELLED", "ROUTED>FAILED");

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    private static String key(OrderStatus from, OrderStatus to) {
        return from + ">" + to;
    }

    @Test
    void thereAreExactlyNineStatusesAndEightyOnePairs() {
        assertEquals(9, OrderStatus.values().length);
        assertEquals(81, allPairs().count());
        assertEquals(14, ALLOWED.size());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void canTransitionToMatchesTheLockedTable(OrderStatus from, OrderStatus to) {
        assertEquals(ALLOWED.contains(key(from, to)), from.canTransitionTo(to));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void requireTransitionToThrowsExactlyForIllegalPairs(OrderStatus from, OrderStatus to) {
        if (ALLOWED.contains(key(from, to))) {
            assertDoesNotThrow(() -> from.requireTransitionTo(to));
        } else {
            InvalidStatusTransitionException e =
                    assertThrows(InvalidStatusTransitionException.class, () -> from.requireTransitionTo(to));
            assertEquals(from, e.getFrom());
            assertEquals(to, e.getTo());
            assertEquals("INVALID_STATUS_TRANSITION", e.getCode());
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void stockIsRestorableOnlyFromHoldingStatusesToCancelledOrFailed(OrderStatus from, OrderStatus to) {
        assertEquals(RESTORING.contains(key(from, to)), from.isStockRestorableTo(to));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyStockRestoringPairIsAlsoALegalTransition(OrderStatus from, OrderStatus to) {
        if (from.isStockRestorableTo(to)) {
            assertTrue(from.canTransitionTo(to), "restoring stock on an illegal edge " + from + " -> " + to);
        }
    }

    @Test
    void noStatusMovesToItself() {
        for (OrderStatus status : OrderStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " -> itself");
        }
    }

    @Test
    void exactlyDeliveredCancelledAndFailedAreTerminalWithNoWayOut() {
        EnumSet<OrderStatus> terminal = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isTerminal()) {
                terminal.add(status);
                assertTrue(status.allowedTargets().isEmpty(), status + " must have no targets");
            }
        }
        assertEquals(EnumSet.of(DELIVERED, CANCELLED, FAILED), terminal);
    }

    @Test
    void nonTerminalIsTheComplementOfTerminal() {
        assertEquals(EnumSet.of(PLACED, VALIDATED, ROUTED, PREPARING, PACKED, OUT_FOR_DELIVERY), OrderStatus.nonTerminal());
    }

    @Test
    void nonTerminalReturnsAFreshSetEachTime() {
        EnumSet<OrderStatus> first = OrderStatus.nonTerminal();
        first.clear();
        assertEquals(6, OrderStatus.nonTerminal().size());
    }

    @Test
    void allowedTargetsIsAnUnmodifiableCopy() {
        Set<OrderStatus> targets = ROUTED.allowedTargets();
        assertEquals(EnumSet.of(PREPARING, CANCELLED, FAILED), targets);
        assertThrows(UnsupportedOperationException.class, () -> targets.add(DELIVERED));
        assertThrows(UnsupportedOperationException.class, () -> targets.remove(PREPARING));
        assertEquals(3, ROUTED.allowedTargets().size(), "the table must not change through the returned set");
    }

    @Test
    void aNullTargetIsNeverAllowed() {
        assertFalse(PLACED.canTransitionTo(null));
        assertThrows(InvalidStatusTransitionException.class, () -> PLACED.requireTransitionTo(null));
    }

    @Test
    void theHappyPathIsConnectedFromPlacedToDelivered() {
        OrderStatus[] path = {PLACED, VALIDATED, ROUTED, PREPARING, PACKED, OUT_FOR_DELIVERY, DELIVERED};
        for (int i = 0; i < path.length - 1; i++) {
            assertTrue(path[i].canTransitionTo(path[i + 1]), path[i] + " -> " + path[i + 1]);
        }
    }

    @Test
    void anOrderBeingPreparedCanNoLongerBeCancelled() {
        assertFalse(PREPARING.canTransitionTo(CANCELLED));
        assertFalse(PACKED.canTransitionTo(CANCELLED));
        assertFalse(OUT_FOR_DELIVERY.canTransitionTo(CANCELLED));
    }
}
