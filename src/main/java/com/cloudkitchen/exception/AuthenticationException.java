package com.cloudkitchen.exception;

/** Login failed. The message is always the same generic text so that it reveals nothing about why. */
public class AuthenticationException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public static final String GENERIC_MESSAGE = "Invalid credentials or account temporarily locked.";

    public AuthenticationException() {
        super("AUTHENTICATION_FAILED", GENERIC_MESSAGE);
    }
}
