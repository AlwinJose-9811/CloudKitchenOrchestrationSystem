package com.cloudkitchen.exception;

/** The configuration file is missing, unreadable or invalid; the application refuses to start. */
public class ConfigurationException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
