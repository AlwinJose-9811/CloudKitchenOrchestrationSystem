package com.cloudkitchen.pipeline;

import com.cloudkitchen.domain.enums.OrderStatus;

/**
 * Test seam inside {@link StationWorker} (SDD 7.11.10): a test installs a hook that throws, or blocks on a latch, at an
 * exact point of one order's processing to kill or stall a worker deterministically. Production wiring never sets
 * one; the default does nothing.
 */
@FunctionalInterface
public interface WorkerHook {

    /** The points of the worker loop that can be interrupted. */
    enum Stage { AFTER_POLL, AFTER_LEASE, BEFORE_RENEW, BEFORE_STEP, AFTER_STEP, BEFORE_PARK_OFFER }

    WorkerHook NO_OP = (stage, owner, orderId, step) -> { };

    /**
     * @param owner   the worker's owner string {@code <CODE>#<generation>}
     * @param orderId the order being handled
     * @param step    the status the worker is leaving at {@code BEFORE_STEP}/{@code AFTER_STEP}, otherwise {@code null}
     */
    void at(Stage stage, String owner, long orderId, OrderStatus step);
}
