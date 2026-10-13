package com.cloudkitchen.pipeline;

import com.cloudkitchen.exception.QueueFullException;

/**
 * The only thing {@code OrderService} knows about the station queues: hand over an order id. The orchestrator
 * implements it; tests replace it with fakes that are always full or that block. No executor or queue type leaks out.
 */
public interface OrderDispatcher {

    /**
     * Puts the order id on the queue of the station that cooks {@code brandCode}.
     *
     * <p>Contract (RR-11): returns normally if and only if the id is in the queue. If it throws
     * {@link QueueFullException} the id was not inserted. If the calling thread is interrupted after a successful
     * insert, the method still returns normally and sets the thread's interrupt flag again.
     *
     * @throws QueueFullException queue full after the timeout, station unavailable or closed, or interrupted before
     *                            insertion
     */
    void dispatch(long orderId, String brandCode) throws QueueFullException;
}
