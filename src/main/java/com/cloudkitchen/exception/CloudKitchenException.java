package com.cloudkitchen.exception;

/** Base class of all application exceptions. */
public class CloudKitchenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CloudKitchenException(String message) {
        super(message);
    }

    public CloudKitchenException(String message, Throwable cause) {
        super(message, cause);
    }
}
