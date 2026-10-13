package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderStatus;

/**
 * A fresh read of the few columns a worker needs to decide its next step (SDD 7.3, RR-4): status, whether a
 * compensation is owed, and the lease epoch and owner. It is a snapshot, never cached.
 */
public record OrderProgress(OrderStatus status, boolean compensationPending, int claimEpoch, String claimedBy) {
}
