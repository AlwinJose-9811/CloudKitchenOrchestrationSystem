package com.cloudkitchen.exception;

import com.cloudkitchen.domain.enums.OrderStatus;

/** An order was asked to move between two statuses that the lifecycle table does not connect. */
public class InvalidStatusTransitionException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    private final OrderStatus from;
    private final OrderStatus to;

    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("INVALID_STATUS_TRANSITION", "Illegal order status transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
