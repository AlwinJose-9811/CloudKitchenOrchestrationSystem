package com.cloudkitchen.exception;

/**
 * The configuration file is missing, unreadable or invalid, and the application refuses to start. Unchecked on
 * purpose: it can only happen during start-up, where nothing can recover from it.
 */
public class ConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
