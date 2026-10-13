package com.cloudkitchen.service;

import com.cloudkitchen.exception.CloudKitchenException;

/**
 * What the web layer may do with the order snapshots (FR-SER-01): write one now. The snapshot code lives in the
 * {@code snapshot} package, which may not depend on services, so {@code AppContext} adapts it to this interface.
 */
@FunctionalInterface
public interface SnapshotControl {

    /** Writes a signed snapshot of the in-flight orders and prunes old ones; returns the file name only, never a path. */
    String writeNow() throws CloudKitchenException;
}
