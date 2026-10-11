package com.cloudkitchen.exception;

/** The structured order log cannot be opened or written (missing or unwritable path, I/O failure). The cause is kept. */
public class EventLogException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public EventLogException(String message, Throwable cause) {
        super("EVENT_LOG", message, cause);
    }
}
