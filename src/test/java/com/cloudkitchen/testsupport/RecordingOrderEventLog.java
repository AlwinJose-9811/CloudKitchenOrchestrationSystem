package com.cloudkitchen.testsupport;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Duration;

/** Keeps every event in memory so tests can assert what was (and was not) logged. */
public class RecordingOrderEventLog implements OrderEventLog {

    public record Event(EventType type, Long orderId, String detail) {
    }

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final Object signal = new Object();

    private volatile java.util.function.Consumer<Event> listener = e -> { };

    /**
     * Runs {@code listener} on the logging thread right after each event is recorded. A test uses it to pause one
     * thread at a precise point of production code that logs (for example between two steps of a repair), with latches.
     */
    public void onEvent(java.util.function.Consumer<Event> listener) {
        this.listener = java.util.Objects.requireNonNull(listener, "listener");
    }

    private void added(Event event) {
        events.add(event);
        synchronized (signal) {
            signal.notifyAll();
        }
        listener.accept(event);
    }

    @Override
    public void log(EventType type) {
        added(new Event(type, null, null));
    }

    @Override
    public void log(EventType type, long orderId) {
        added(new Event(type, orderId, null));
    }

    @Override
    public void log(EventType type, long orderId, String detail) {
        added(new Event(type, orderId, detail));
    }

    @Override
    public void close() {
        // nothing to release
    }

    public List<Event> all() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public List<EventType> types() {
        return all().stream().map(Event::type).toList();
    }

    public long count(EventType type) {
        return all().stream().filter(e -> e.type() == type).count();
    }

    /**
     * Blocks until an event of the type exists for the order (or, with a null order id, anywhere), or the bound passes.
     * This is real synchronisation on the log's own signal, not a sleep: events are emitted after commit, so a
     * returned {@code true} means the change is visible to a new connection.
     */
    public boolean await(EventType type, Long orderId, Duration timeout) throws InterruptedException {
        return awaitCount(type, orderId, 1, timeout);
    }

    public boolean awaitCount(EventType type, Long orderId, long atLeast, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (signal) {
            while (countFor(type, orderId) < atLeast) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                signal.wait(Math.max(1, remaining / 1_000_000));
            }
            return true;
        }
    }

    /**
     * Blocks until the condition holds or the bound passes. The condition is evaluated while holding the signal's
     * monitor and every {@code log} call notifies under that monitor after adding its event, so no wake-up is lost.
     */
    public boolean awaitUntil(Duration timeout, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (signal) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                signal.wait(Math.max(1, remaining / 1_000_000));
            }
            return true;
        }
    }

    /** Wakes every waiter so that it re-evaluates its condition (for state changes that are not events). */
    public void poke() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    public long countFor(EventType type, Long orderId) {
        return all().stream().filter(e -> e.type() == type && (orderId == null || orderId.equals(e.orderId()))).count();
    }

    public void clear() {
        events.clear();
        listener = e -> { };
    }
}
