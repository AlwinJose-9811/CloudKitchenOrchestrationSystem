package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderStatus;
import java.util.List;
import java.util.Set;

/**
 * What a caller learns about a submission. Create it only through the static factories, which keep the combination of
 * fields consistent (for example an {@code ACCEPTED} result always has an order id and never has errors).
 */
public record SubmissionResult(Outcome outcome, Long orderId, boolean compensated, OrderStatus existingStatus,
                               boolean compensationPending, String reasonCode, List<FieldError> errors) {

    /** How the submission ended. */
    public enum Outcome {
        ACCEPTED, DUPLICATE, REJECTED, QUEUE_FULL,
        /** The order exists but neither FAILED nor the PENDING marker could be committed; nothing was promised. */
        INDETERMINATE
    }

    public static final String VALIDATION = "VALIDATION";
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String TEMPORARY_FAILURE = "TEMPORARY_FAILURE";
    public static final String STARTING_UP = "STARTING_UP";
    public static final String SHUTTING_DOWN = "SHUTTING_DOWN";
    public static final String QUEUE_FULL = "QUEUE_FULL";

    private static final Set<String> REJECTION_REASONS =
            Set.of(VALIDATION, INSUFFICIENT_STOCK, TEMPORARY_FAILURE, STARTING_UP, SHUTTING_DOWN);

    public SubmissionResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static SubmissionResult accepted(long orderId) {
        return new SubmissionResult(Outcome.ACCEPTED, orderId, false, null, false, null, List.of());
    }

    public static SubmissionResult duplicate(long existingOrderId, OrderStatus existingStatus,
                                             boolean compensationPending) {
        return new SubmissionResult(Outcome.DUPLICATE, existingOrderId, false, existingStatus,
                compensationPending, null, List.of());
    }

    public static SubmissionResult indeterminate(long orderId) {
        return new SubmissionResult(Outcome.INDETERMINATE, orderId, false, null, false, TEMPORARY_FAILURE, List.of());
    }

    /** A refusal that created nothing, with the field errors that explain it. */
    public static SubmissionResult rejected(String reasonCode, List<FieldError> errors) {
        if (reasonCode == null || !REJECTION_REASONS.contains(reasonCode)) {
            throw new IllegalArgumentException("not a rejection reason: " + reasonCode);
        }
        return new SubmissionResult(Outcome.REJECTED, null, false, null, false, reasonCode, errors);
    }

    /** A refusal that has no field errors (shutting down, temporary failure, insufficient stock). */
    public static SubmissionResult rejected(String reasonCode) {
        return rejected(reasonCode, List.of());
    }

    /**
     * The order was created but its station queue did not take it.
     *
     * @param compensated {@code true} when the order was marked FAILED and stock restored; {@code false} when only the
     *                    durable PENDING marker could be committed
     */
    public static SubmissionResult queueFull(long orderId, boolean compensated) {
        return new SubmissionResult(Outcome.QUEUE_FULL, orderId, compensated, null, !compensated, QUEUE_FULL, List.of());
    }
}
