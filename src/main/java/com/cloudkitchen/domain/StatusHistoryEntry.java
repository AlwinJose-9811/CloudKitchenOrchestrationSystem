package com.cloudkitchen.domain;

import com.cloudkitchen.domain.enums.OrderStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of an order's history. {@code from} is {@code null} only for the first entry, which must lead to
 * {@code PLACED}; every other entry must be an edge of the lifecycle table.
 */
public record StatusHistoryEntry(long orderId, OrderStatus from, OrderStatus to, Instant changedAt, String actor,
                                 String note) {

    public static final int MAX_ACTOR_LENGTH = 50;
    public static final int MAX_NOTE_LENGTH = 255;

    public StatusHistoryEntry {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(changedAt, "changedAt");
        if (from == null && to != OrderStatus.PLACED) {
            throw new IllegalArgumentException("only the first entry may have no 'from', and it must lead to PLACED");
        }
        if (from != null && !from.canTransitionTo(to)) {
            throw new IllegalArgumentException("not a lifecycle edge: " + from + " -> " + to);
        }
        if (actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH) {
            throw new IllegalArgumentException("actor must be 1-" + MAX_ACTOR_LENGTH + " characters");
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
    }
}
