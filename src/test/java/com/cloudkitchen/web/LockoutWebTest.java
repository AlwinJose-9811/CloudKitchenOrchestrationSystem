package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** FR-AUT-07 (web part), SDD 12.2: five failures lock the account for five minutes, also under concurrent login attempts. */
class LockoutWebTest extends WebTestBase {

    private static final String GENERIC = AuthenticationException.GENERIC_MESSAGE;

    @BeforeEach
    void resetCounters() throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("UPDATE staff_users SET failed_attempts = 0, locked_until = NULL")) {
            p.executeUpdate();
        }
    }

    private record Counters(int failed, Instant lockedUntil) {
    }

    private Counters counters(String username) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("SELECT failed_attempts, locked_until FROM staff_users WHERE username = ?")) {
            p.setString(1, username);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                Timestamp until = rs.getTimestamp(2);
                return new Counters(rs.getInt(1), until == null ? null : until.toInstant());
            }
        }
    }

    private static Reply attempt(HttpTestClient browser, String token, String username, String password) {
        return browser.postForm("/login", Map.of("username", username, "password", password, "_csrf", token));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountAndEvenTheCorrectPasswordIsThenRefusedWithTheSameMessage() throws Exception {
        for (int i = 1; i <= 5; i++) {
            HttpTestClient browser = app.browser();
            String token = HttpTestClient.extractCsrf(browser.get("/login").body());
            HttpTestClient.assertRedirect(attempt(browser, token, app.staffUsername(), "wrong-" + i), "/login");
            assertEquals(i, counters(app.staffUsername()).failed());
        }
        Counters locked = counters(app.staffUsername());
        assertNotNull(locked.lockedUntil());
        assertTrue(locked.lockedUntil().isAfter(Instant.now().plusSeconds(240)) && locked.lockedUntil().isBefore(Instant.now().plusSeconds(330)),
                "about five minutes: " + locked.lockedUntil());

        HttpTestClient browser = app.browser();
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        HttpTestClient.assertRedirect(attempt(browser, token, app.staffUsername(), app.staffPassword()), "/login");
        assertTrue(browser.get("/login").body().contains(GENERIC), "the same generic message while locked");
        assertEquals(5, counters(app.staffUsername()).failed(), "attempts while locked are not counted further");
    }

    @Test
    void theLockEndsAfterFiveMinutesAndASuccessfulLoginResetsTheCounter() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpTestClient browser = app.browser();
            attempt(browser, HttpTestClient.extractCsrf(browser.get("/login").body()), app.staffUsername(), "wrong");
        }
        assertNotNull(counters(app.staffUsername()).lockedUntil());
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("UPDATE staff_users SET locked_until = ? WHERE username = ?")) {
            p.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(1)));
            p.setString(2, app.staffUsername());
            p.executeUpdate();
        }
        HttpTestClient browser = app.browser();
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        HttpTestClient.assertRedirect(attempt(browser, token, app.staffUsername(), app.staffPassword()), "/dashboard");
        Counters after = counters(app.staffUsername());
        assertEquals(0, after.failed());
        assertNull(after.lockedUntil());
    }

    @Test
    void aSuccessfulLoginInTheMiddleResetsTheFailureCount() throws Exception {
        for (int i = 0; i < 4; i++) {
            HttpTestClient browser = app.browser();
            attempt(browser, HttpTestClient.extractCsrf(browser.get("/login").body()), app.staffUsername(), "wrong");
        }
        assertEquals(4, counters(app.staffUsername()).failed());
        app.staffBrowser();
        assertEquals(0, counters(app.staffUsername()).failed());
        HttpTestClient browser = app.browser();
        attempt(browser, HttpTestClient.extractCsrf(browser.get("/login").body()), app.staffUsername(), "wrong");
        assertEquals(1, counters(app.staffUsername()).failed(), "the earlier failures do not add up to a lock");
    }

    @Test
    void lockingOneAccountDoesNotAffectAnotherAndNotAnExistingSession() throws Exception {
        HttpTestClient staffSession = app.staffBrowser();
        for (int i = 0; i < 5; i++) {
            HttpTestClient browser = app.browser();
            attempt(browser, HttpTestClient.extractCsrf(browser.get("/login").body()), app.staffUsername(), "wrong");
        }
        assertNotNull(counters(app.staffUsername()).lockedUntil());
        assertEquals(200, staffSession.get("/inventory").status(), "a lock affects login only, not a session that already exists (FR-AUT-10)");
        app.adminBrowser();
        assertEquals(0, counters(app.adminUsername()).failed());
    }

    @Test
    void anUnknownUserNeverCreatesLockStateAndCannotBeToldApartFromAWrongPassword() throws Exception {
        for (int i = 0; i < 7; i++) {
            HttpTestClient browser = app.browser();
            Reply reply = attempt(browser, HttpTestClient.extractCsrf(browser.get("/login").body()), "ghost", "wrong");
            HttpTestClient.assertRedirect(reply, "/login");
            assertTrue(browser.get("/login").body().contains(GENERIC));
        }
    }

    /**
     * Parallel wrong attempts on one account, each from its own browser with its own session and token: the row lock of the
     * login transaction makes the count exact, so the account is locked at exactly five failures however the attempts interleave.
     */
    @RepeatedTest(20)
    void concurrentWrongAttemptsCountExactlyFiveAndLockOnce() throws Exception {
        int attempts = 6;
        List<HttpTestClient> browsers = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            HttpTestClient browser = app.browser();
            tokens.add(HttpTestClient.extractCsrf(browser.get("/login").body()));
            browsers.add(browser);
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts, r -> new Thread(r, "lockout-test"));
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                HttpTestClient browser = browsers.get(i);
                String token = tokens.get(i);
                replies.add(pool.submit(() -> {
                    go.await();
                    return attempt(browser, token, app.staffUsername(), "wrong");
                }));
            }
            go.countDown();
            for (Future<Reply> reply : replies) {
                HttpTestClient.assertRedirect(reply.get(60, TimeUnit.SECONDS), "/login");
            }
        } finally {
            pool.shutdownNow();
        }
        Counters after = counters(app.staffUsername());
        assertEquals(5, after.failed(), "exactly five failures are counted, the others met the lock");
        assertNotNull(after.lockedUntil(), "the account is locked once the fifth failure is counted");
    }

    @RepeatedTest(20)
    void parallelAttemptsWithTheRightPasswordAllSucceedAndLeaveACleanCounter() throws Exception {
        int attempts = 3;
        List<HttpTestClient> browsers = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            HttpTestClient browser = app.browser();
            tokens.add(HttpTestClient.extractCsrf(browser.get("/login").body()));
            browsers.add(browser);
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts, r -> new Thread(r, "login-test"));
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                HttpTestClient browser = browsers.get(i);
                String token = tokens.get(i);
                replies.add(pool.submit(() -> {
                    go.await();
                    return attempt(browser, token, app.adminUsername(), app.adminPassword());
                }));
            }
            go.countDown();
            for (Future<Reply> reply : replies) {
                HttpTestClient.assertRedirect(reply.get(60, TimeUnit.SECONDS), "/dashboard");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, counters(app.adminUsername()).failed());
    }
}
