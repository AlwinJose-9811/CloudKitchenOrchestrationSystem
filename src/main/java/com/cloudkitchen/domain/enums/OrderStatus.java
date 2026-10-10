package com.cloudkitchen.domain.enums;

import com.cloudkitchen.exception.InvalidStatusTransitionException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an order. The transition table below is the only place the rules exist: every other class asks
 * this enum. {@code DELIVERED}, {@code CANCELLED} and {@code FAILED} are terminal.
 */
public enum OrderStatus {
    PLACED, VALIDATED, ROUTED, PREPARING, PACKED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, FAILED;

    private static final Map<OrderStatus, EnumSet<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(PLACED, EnumSet.of(VALIDATED, FAILED, CANCELLED));
        TRANSITIONS.put(VALIDATED, EnumSet.of(ROUTED, CANCELLED, FAILED));
        TRANSITIONS.put(ROUTED, EnumSet.of(PREPARING, CANCELLED, FAILED));
        TRANSITIONS.put(PREPARING, EnumSet.of(PACKED, FAILED));
        TRANSITIONS.put(PACKED, EnumSet.of(OUT_FOR_DELIVERY));
        TRANSITIONS.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, FAILED));
        TRANSITIONS.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(FAILED, EnumSet.noneOf(OrderStatus.class));
    }

    /** True iff the locked table allows moving from this status to {@code target}. A status never moves to itself. */
    public boolean canTransitionTo(OrderStatus target) {
        return target != null && TRANSITIONS.get(this).contains(target);
    }

    /** Throws {@link InvalidStatusTransitionException} unless {@link #canTransitionTo(OrderStatus)} holds. */
    public void requireTransitionTo(OrderStatus target) throws InvalidStatusTransitionException {
        if (!canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(this, target);
        }
    }

    /** The statuses reachable in one step, as an unmodifiable copy. */
    public Set<OrderStatus> allowedTargets() {
        return Collections.unmodifiableSet(EnumSet.copyOf(TRANSITIONS.get(this)));
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /**
     * Whether moving from this status to {@code target} gives back reserved stock: only {@code VALIDATED} and
     * {@code ROUTED} hold stock, and only {@code CANCELLED} and {@code FAILED} release it. {@code PLACED} never holds
     * stock (it exists only inside the creation transaction before the deduction commits); {@code PREPARING} and later
     * never restore.
     */
    public boolean isStockRestorableTo(OrderStatus target) {
        return (this == VALIDATED || this == ROUTED) && (target == CANCELLED || target == FAILED);
    }

    /** Every status that is not terminal: the set recovery reloads at start-up. */
    public static EnumSet<OrderStatus> nonTerminal() {
        EnumSet<OrderStatus> result = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : values()) {
            if (!status.isTerminal()) {
                result.add(status);
            }
        }
        return result;
    }
}
