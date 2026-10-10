package com.cloudkitchen.exception;

/** The same external order (source and reference) was submitted again. */
public class DuplicateOrderException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    private final Long existingOrderId;

    /** @param existingOrderId the id of the order that already exists, or {@code null} when it is not known */
    public DuplicateOrderException(Long existingOrderId) {
        super("DUPLICATE_ORDER", "This external order was already submitted");
        this.existingOrderId = existingOrderId;
    }

    public Long getExistingOrderId() {
        return existingOrderId;
    }
}
