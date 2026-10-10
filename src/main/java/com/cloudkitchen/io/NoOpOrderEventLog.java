package com.cloudkitchen.io;

/** Null object: accepts every event and records nothing. Used by unit tests and before the file log exists. */
public final class NoOpOrderEventLog implements OrderEventLog {

    @Override
    public void log(EventType type) {
        // intentionally empty
    }

    @Override
    public void log(EventType type, long orderId) {
        // intentionally empty
    }

    @Override
    public void log(EventType type, long orderId, String detail) {
        // intentionally empty
    }

    @Override
    public void close() {
        // intentionally empty
    }
}
