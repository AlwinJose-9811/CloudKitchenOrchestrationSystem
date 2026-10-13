package com.cloudkitchen.web.support;

/** Names of the HTTP session attributes (SDD 10.4); the CSRF token and the flash messages have their own classes. */
public final class SessionKeys {

    /** The logged-in {@link com.cloudkitchen.dto.SessionUser}, including the session version checked on every request. */
    public static final String USER = "ck.user";

    private SessionKeys() {
    }
}
