package com.cloudkitchen.service;

import com.cloudkitchen.dto.PendingCompensationView;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.CloudKitchenException;
import java.util.List;

/**
 * The only manual operations on an order that is awaiting compensation (SDD 7.10.11, FR-WEB-14): list the pending orders
 * and retry one. There is deliberately nothing that clears the marker or re-queues the order. Implemented by
 * {@link OrderService}; the compensation page depends on this narrow type.
 */
public interface CompensationOperator {

    /** What a retry did. */
    enum Outcome {
        /** This call turned the order into FAILED and gave its stock back. */
        RESOLVED,
        /** The order was not pending any more (somebody else resolved it first, or it never was): nothing was changed. */
        ALREADY_RESOLVED
    }

    /** The orders awaiting compensation, oldest id first. */
    List<PendingCompensationView> pendingCompensations() throws CloudKitchenException;

    /**
     * ADMIN only (re-checked here): the standard compensation transaction, ignoring the backoff but never the restore guard.
     *
     * @throws CloudKitchenException {@code FORBIDDEN} for a non-administrator; the persistence error if the attempt failed
     *                               (the failed attempt is then recorded for the backoff bookkeeping)
     */
    Outcome retry(long orderId, SessionUser admin) throws CloudKitchenException;
}
