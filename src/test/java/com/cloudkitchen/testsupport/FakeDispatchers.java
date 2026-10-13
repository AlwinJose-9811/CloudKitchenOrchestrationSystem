package com.cloudkitchen.testsupport;

import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.pipeline.OrderDispatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Hand-written {@link OrderDispatcher} fakes (no mocking framework): accepting, always full, and programmable. */
public final class FakeDispatchers {

    private FakeDispatchers() {
    }

    /** Takes every id and remembers it; the real orchestrator arrives in Phase 4. */
    public static final class Accepting implements OrderDispatcher {
        private final List<Long> dispatched = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void dispatch(long orderId, String brandCode) {
            dispatched.add(orderId);
        }

        public List<Long> dispatched() {
            synchronized (dispatched) {
                return new ArrayList<>(dispatched);
            }
        }
    }

    /** A dispatcher whose queue is always full (RR-11: nothing is inserted when it throws). */
    public static final class AlwaysFull implements OrderDispatcher {
        private final QueueFullException.Reason reason;

        public AlwaysFull() {
            this(QueueFullException.Reason.QUEUE_FULL);
        }

        public AlwaysFull(QueueFullException.Reason reason) {
            this.reason = reason;
        }

        @Override
        public void dispatch(long orderId, String brandCode) throws QueueFullException {
            throw new QueueFullException(brandCode, reason);
        }
    }

    /** Behaviour can be switched during a test. */
    public static final class Switchable implements OrderDispatcher {
        private final AtomicReference<OrderDispatcher> current = new AtomicReference<>(new Accepting());

        public void use(OrderDispatcher dispatcher) {
            current.set(dispatcher);
        }

        @Override
        public void dispatch(long orderId, String brandCode) throws QueueFullException {
            current.get().dispatch(orderId, brandCode);
        }
    }

    /**
     * Holds the submitting thread between a successful dispatch and the submitter's ROUTED update (the window of
     * SDD 7.8.4/7.8.5): the real dispatcher inserts the id, then this class announces it and waits for the test.
     */
    public static final class Gated implements OrderDispatcher {
        private final OrderDispatcher delegate;
        public final CountDownLatch dispatched = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);

        public Gated(OrderDispatcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public void dispatch(long orderId, String brandCode) throws QueueFullException {
            delegate.dispatch(orderId, brandCode);
            dispatched.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Holds the submitting thread before the dispatch (the order is committed but not yet queued). */
    public static final class Blocking implements OrderDispatcher {
        private final OrderDispatcher delegate;
        public final CountDownLatch arrived = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);

        public Blocking(OrderDispatcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public void dispatch(long orderId, String brandCode) throws QueueFullException {
            arrived.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueueFullException(brandCode, QueueFullException.Reason.INTERRUPTED, e);
            }
            delegate.dispatch(orderId, brandCode);
        }
    }
}
