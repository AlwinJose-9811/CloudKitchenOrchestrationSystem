package com.cloudkitchen.web.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.FakeSession;
import jakarta.servlet.http.HttpSession;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SDD 12.4: one unpredictable token per session, rotated at login, compared in constant time. */
class CsrfTokensTest {

    @Test
    void ensureCreatesOneTokenOf32RandomBytesAndKeepsIt() {
        HttpSession session = FakeSession.create();
        String token = CsrfTokens.ensure(session);
        assertEquals(32, Base64.getUrlDecoder().decode(token).length);
        assertEquals(token, CsrfTokens.ensure(session), "the same session keeps its token");
        assertEquals(token, session.getAttribute(CsrfTokens.ATTRIBUTE));
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), "URL-safe Base64 without padding: " + token);
    }

    @Test
    void tokensOfDifferentSessionsAndRotationsDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            assertTrue(seen.add(CsrfTokens.ensure(FakeSession.create())));
        }
        HttpSession session = FakeSession.create();
        String before = CsrfTokens.ensure(session);
        String after = CsrfTokens.rotate(session);
        assertNotEquals(before, after);
        assertFalse(CsrfTokens.matches(session, before), "the token seen before login is useless afterwards");
        assertTrue(CsrfTokens.matches(session, after));
    }

    @Test
    void onlyTheExactTokenMatches() {
        HttpSession session = FakeSession.create();
        String token = CsrfTokens.ensure(session);
        assertTrue(CsrfTokens.matches(session, token));
        assertFalse(CsrfTokens.matches(session, null));
        assertFalse(CsrfTokens.matches(session, ""));
        assertFalse(CsrfTokens.matches(session, token + "x"));
        assertFalse(CsrfTokens.matches(session, token.substring(1)));
        assertFalse(CsrfTokens.matches(session, token.toLowerCase().equals(token) ? token.toUpperCase() : token.toLowerCase()));
        assertFalse(CsrfTokens.matches(session, CsrfTokens.ensure(FakeSession.create())), "a token of another session");
    }

    @Test
    void aSessionWithoutATokenOrANullSessionNeverMatches() {
        assertFalse(CsrfTokens.matches(FakeSession.create(), "anything"));
        assertFalse(CsrfTokens.matches(null, "anything"));
        HttpSession session = FakeSession.create();
        session.setAttribute(CsrfTokens.ATTRIBUTE, "");
        assertFalse(CsrfTokens.matches(session, ""), "an empty stored token is not a token");
        session.setAttribute(CsrfTokens.ATTRIBUTE, 42);
        assertFalse(CsrfTokens.matches(session, "42"));
    }

    @Test
    void concurrentEnsureCallsOnOneSessionAgreeOnOneToken() throws Exception {
        HttpSession session = FakeSession.create();
        int threads = 16;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> new Thread(r, "csrf-test"));
        try {
            java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return CsrfTokens.ensure(session);
                }));
            }
            go.countDown();
            Set<String> tokens = new HashSet<>();
            for (var future : results) {
                tokens.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(1, tokens.size(), "all callers must see the same token");
        } finally {
            pool.shutdownNow();
        }
    }
}
