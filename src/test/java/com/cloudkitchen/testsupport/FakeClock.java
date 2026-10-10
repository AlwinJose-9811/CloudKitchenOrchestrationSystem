package com.cloudkitchen.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that only moves when a test says so; safe to share between threads. */
public final class FakeClock extends Clock {

    private final AtomicReference<Instant> now;

    public FakeClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public FakeClock() {
        this(Instant.parse("2026-10-10T12:00:00Z"));
    }

    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
