package com.cloudkitchen.io;

/**
 * Append-only structured event log. Implementations must be thread-safe. Callers pass only ids, codes and counts:
 * never customer names, addresses, passwords, hashes, secrets or session ids.
 */
public interface OrderEventLog {

    void log(EventType type);

    void log(EventType type, long orderId);

    void log(EventType type, long orderId, String detail);

    /** Releases the file; calling it again does nothing. */
    void close();
}
