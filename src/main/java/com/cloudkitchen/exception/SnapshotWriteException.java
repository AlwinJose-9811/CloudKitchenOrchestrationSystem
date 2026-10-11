package com.cloudkitchen.exception;

/** A snapshot could not be written (directory, disk, permissions) or its source data could not be read. The cause is kept. */
public class SnapshotWriteException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public SnapshotWriteException(String message, Throwable cause) {
        super("SNAPSHOT_WRITE", message, cause);
    }
}
