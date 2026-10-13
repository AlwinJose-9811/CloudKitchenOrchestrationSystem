package com.cloudkitchen.web.support;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The synchronizer token of SDD 12.4: 32 random bytes (Base64url) per session, kept in the session attribute
 * {@value #ATTRIBUTE} and compared with {@link MessageDigest#isEqual}. The token is rotated at login so that a token
 * seen before authentication is useless afterwards. Token values are never logged.
 */
public final class CsrfTokens {

    public static final String ATTRIBUTE = "ck.csrf";
    /** The name of the hidden form field and of the request parameter. */
    public static final String PARAMETER = "_csrf";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private CsrfTokens() {
    }

    /** The session's token, created on first use. */
    public static String ensure(HttpSession session) {
        synchronized (LOCK) {
            Object existing = session.getAttribute(ATTRIBUTE);
            if (existing instanceof String token && !token.isEmpty()) {
                return token;
            }
            return store(session);
        }
    }

    /** Replaces the token with a fresh one (login). */
    public static String rotate(HttpSession session) {
        synchronized (LOCK) {
            return store(session);
        }
    }

    /** {@code true} only if the session has a token and {@code candidate} equals it; a missing candidate never matches. */
    public static boolean matches(HttpSession session, String candidate) {
        if (session == null || candidate == null) {
            return false;
        }
        Object token = session.getAttribute(ATTRIBUTE);
        if (!(token instanceof String expected) || expected.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String store(HttpSession session) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(ATTRIBUTE, token);
        return token;
    }
}
