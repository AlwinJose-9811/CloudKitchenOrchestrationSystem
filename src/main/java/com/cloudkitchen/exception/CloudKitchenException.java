package com.cloudkitchen.exception;

/**
 * Base class of the application's checked exceptions. The {@code code} is a stable, upper-case identifier that
 * callers and tests can rely on; the message is for people and may change.
 */
public class CloudKitchenException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    public CloudKitchenException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public CloudKitchenException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("exception code must not be blank");
        }
        return code;
    }

    public String getCode() {
        return code;
    }
}
