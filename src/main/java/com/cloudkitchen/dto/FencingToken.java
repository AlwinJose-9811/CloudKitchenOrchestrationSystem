package com.cloudkitchen.dto;

import java.util.Objects;

/**
 * Proof that a worker owns an order's lease: the order, the owner string {@code <CODE>#<generation>} and the epoch
 * read back after acquiring (SDD 7.11.3). The DAO interfaces name it, so it exists from Phase 2.
 */
public record FencingToken(long orderId, String owner, int epoch) {

    public FencingToken {
        Objects.requireNonNull(owner, "owner");
    }
}
