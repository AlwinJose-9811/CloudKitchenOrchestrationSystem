package com.cloudkitchen.web.support;

import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A one-time message shown after a redirect (SDD 10.4). The text is escaped on output and must not contain sensitive
 * data. Messages live in the session attribute {@value #ATTRIBUTE} and are removed by the first {@link #consume}.
 */
public record Flash(Type type, String text) implements java.io.Serializable {

    public static final String ATTRIBUTE = "ck.flash";
    private static final Object LOCK = new Object();
    private static final int MAX_PENDING = 20;

    /** The kind of message; the lower-case name is the CSS class. */
    public enum Type {
        SUCCESS, INFO, ERROR;

        public String cssClass() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public Flash {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
    }

    // Jakarta EL 5.0 (Tomcat 10.1) reads JavaBean getters, not record accessors, so the views use these.

    public String getText() {
        return text;
    }

    public String getCssClass() {
        return type.cssClass();
    }

    /** Appends a message (at most {@value #MAX_PENDING} wait; the oldest is dropped). */
    public static void add(HttpSession session, Type type, String text) {
        synchronized (LOCK) {
            List<Flash> next = new ArrayList<>(pending(session));
            next.add(new Flash(type, text));
            while (next.size() > MAX_PENDING) {
                next.remove(0);
            }
            session.setAttribute(ATTRIBUTE, List.copyOf(next));
        }
    }

    /** Returns and removes all pending messages. */
    public static List<Flash> consume(HttpSession session) {
        if (session == null) {
            return List.of();
        }
        synchronized (LOCK) {
            List<Flash> messages = pending(session);
            if (!messages.isEmpty()) {
                session.removeAttribute(ATTRIBUTE);
            }
            return messages;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Flash> pending(HttpSession session) {
        Object value = session.getAttribute(ATTRIBUTE);
        return value instanceof List<?> list ? (List<Flash>) list : List.of();
    }
}
