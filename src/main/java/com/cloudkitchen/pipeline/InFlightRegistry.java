package com.cloudkitchen.pipeline;

import com.cloudkitchen.domain.enums.OrderSource;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of (source, external reference) pairs being submitted right now. It is the fast, in-JVM half of duplicate
 * protection; the database's {@code UNIQUE (source, external_ref)} is the authoritative half. The reference is
 * case-sensitive, exactly as in the database, and surrounding whitespace is ignored.
 */
public final class InFlightRegistry {

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    /** @return {@code true} for exactly one of any number of concurrent callers with the same key */
    public boolean tryAcquire(OrderSource source, String externalRef) {
        return keys.add(key(source, externalRef));
    }

    /** Frees the key; releasing a key that is not held does nothing. */
    public void release(OrderSource source, String externalRef) {
        keys.remove(key(source, externalRef));
    }

    public boolean isInFlight(OrderSource source, String externalRef) {
        return keys.contains(key(source, externalRef));
    }

    public int size() {
        return keys.size();
    }

    private static String key(OrderSource source, String externalRef) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(externalRef, "externalRef");
        return source.name() + ':' + externalRef.strip();
    }
}
