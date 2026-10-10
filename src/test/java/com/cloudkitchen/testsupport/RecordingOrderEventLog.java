package com.cloudkitchen.testsupport;

import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps every event in memory so tests can assert what was (and was not) logged. */
public class RecordingOrderEventLog implements OrderEventLog {

    public record Event(EventType type, Long orderId, String detail) {
    }

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void log(EventType type) {
        events.add(new Event(type, null, null));
    }

    @Override
    public void log(EventType type, long orderId) {
        events.add(new Event(type, orderId, null));
    }

    @Override
    public void log(EventType type, long orderId, String detail) {
        events.add(new Event(type, orderId, detail));
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

    public void clear() {
        events.clear();
    }
}
