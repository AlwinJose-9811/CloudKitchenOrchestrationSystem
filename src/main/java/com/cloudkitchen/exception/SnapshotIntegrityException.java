package com.cloudkitchen.exception;

/** A snapshot file failed its integrity check (bad signature, truncated, or rejected by the class filter). */
public class SnapshotIntegrityException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public SnapshotIntegrityException(String message) {
        super("SNAPSHOT_INTEGRITY", message);
    }

    public SnapshotIntegrityException(String message, Throwable cause) {
        super("SNAPSHOT_INTEGRITY", message, cause);
    }
}
