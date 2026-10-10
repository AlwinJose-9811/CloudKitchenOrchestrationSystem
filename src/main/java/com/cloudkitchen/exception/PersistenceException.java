package com.cloudkitchen.exception;

/**
 * A database operation failed for a reason the caller cannot fix (deadlock retries used up, connection lost). It
 * wraps the underlying cause; the cause is typed {@link Throwable} so that this package needs no {@code java.sql}.
 */
public class PersistenceException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super("PERSISTENCE", message, cause);
    }

    protected PersistenceException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
