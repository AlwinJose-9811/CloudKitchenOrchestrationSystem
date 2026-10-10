package com.cloudkitchen.testsupport;

import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.pipeline.OrderDispatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
}
