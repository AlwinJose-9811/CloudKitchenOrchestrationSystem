package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthServiceTest {

    // Test-only throw-away values; they exist nowhere outside this class and the test database.
    private static final String GOOD = "correct-horse-battery-1";

    private FakeClock clock;
    private DbFixture db;
    private DbTestSupport t;
    private PasswordHasher hasher;
    private AuthService auth;
    private String storedHash;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
        t = new DbTestSupport(db);
        hasher = new PasswordHasher();
        storedHash = hasher.hash(GOOD.toCharArray());
        auth = new AuthService(db.database(), t.users, hasher, new LockoutPolicy(), clock);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
        clock.set(Instant.parse("2026-10-10T12:00:00Z"));
        db.database().inTransaction(c -> {
            t.users.insert(c, "alice", storedHash, Role.ADMIN);
            t.users.insert(c, "cook", storedHash, Role.KITCHEN_STAFF);
            return null;
        });
    }

    private SessionUser login(String user, String password) throws CloudKitchenException {
        return auth.authenticate(user, password.toCharArray());
    }

    private void assertRefused(String user, String password) {
        AuthenticationException e = assertThrows(AuthenticationException.class, () -> login(user, password));
        assertEquals(AuthenticationException.GENERIC_MESSAGE, e.getMessage());
    }

    @Test
    void aCorrectLoginReturnsTheSessionUserAndRecordsTheLoginTime() throws Exception {
        SessionUser user = login("  Alice ", GOOD);
        assertEquals("alice", user.username());
        assertEquals(Role.ADMIN, user.role());
        assertEquals(0, user.sessionVersion());
        assertTrue(user.userId() > 0);
        assertTrue(t.text("SELECT last_login_at FROM staff_users WHERE username = 'alice'").startsWith("2026-10-10 12:00:00"));
        assertEquals(0, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'alice'"));
    }

    @Test
    void thePasswordArrayIsWipedAfterEveryAttempt() throws Exception {
        char[] ok = GOOD.toCharArray();
        auth.authenticate("alice", ok);
        assertArrayEquals(new char[ok.length], ok);
        char[] bad = "nope-nope-nope-1".toCharArray();
        assertThrows(AuthenticationException.class, () -> auth.authenticate("alice", bad));
        assertArrayEquals(new char[bad.length], bad);
        char[] unknown = "whatever-1234".toCharArray();
        assertThrows(AuthenticationException.class, () -> auth.authenticate("ghost", unknown));
        assertArrayEquals(new char[unknown.length], unknown);
    }

    @Test
    void unknownUserWrongPasswordLockedAndDisabledAllLookIdentical() throws Exception {
        db.database().inTransaction(c -> t.users.insert(c, "locked", storedHash, Role.KITCHEN_STAFF));
        db.database().inTransaction(c -> t.users.insert(c, "off", storedHash, Role.KITCHEN_STAFF));
        t.execute("UPDATE staff_users SET enabled = 0 WHERE username = 'off'");
        for (int i = 0; i < 5; i++) {
            assertRefused("locked", "wrong-password-1");
        }
        List<String> messages = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (String[] attempt : new String[][] {{"ghost", GOOD}, {"alice", "wrong-password-1"}, {"locked", GOOD}, {"off", GOOD}}) {
            AuthenticationException e = assertThrows(AuthenticationException.class, () -> login(attempt[0], attempt[1]));
            messages.add(e.getMessage());
            codes.add(e.getCode());
        }
        assertEquals(1, messages.stream().distinct().count(), "NFR-SEC-03: one message for all four cases");
        assertEquals(1, codes.stream().distinct().count());
        assertEquals("Invalid credentials or account temporarily locked.", messages.get(0));
    }

    @Test
    void everyRefusalVerifiesAHashSoUnknownUsersAreNotFasterThanWrongPasswords() throws Exception {
        login("alice", GOOD); // warm up the JIT and the dummy hash path
        assertRefused("ghost", GOOD);
        long unknown = timeOf(() -> assertRefused("ghost", GOOD));
        long wrong = timeOf(() -> assertRefused("alice", "wrong-password-1"));
        assertTrue(unknown > 40, "an unknown user still costs a PBKDF2 verification: " + unknown + " ms");
        assertTrue(wrong > 40, "a wrong password costs one too: " + wrong + " ms");
        assertTrue(Math.abs(unknown - wrong) < Math.max(unknown, wrong) * 0.9, "similar order of magnitude: " + unknown + " vs " + wrong);
    }

    private static long timeOf(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void theFifthFailureLocksForFiveMinutesEvenTheCorrectPasswordIsThenRefused() throws Exception {
        for (int i = 1; i <= 4; i++) {
            assertRefused("alice", "wrong-password-1");
            assertEquals(i, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'alice'"));
            assertNull(t.text("SELECT locked_until FROM staff_users WHERE username = 'alice'"));
        }
        assertRefused("alice", "wrong-password-1");
        assertEquals(5, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'alice'"));
        assertTrue(t.text("SELECT locked_until FROM staff_users WHERE username = 'alice'").startsWith("2026-10-10 12:05:00"));
        assertRefused("alice", GOOD);
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertRefused("alice", GOOD);
        assertTrue(t.text("SELECT locked_until FROM staff_users WHERE username = 'alice'").startsWith("2026-10-10 12:05:00"), "attempts while locked do not extend the lock");
        clock.advance(Duration.ofSeconds(1));
        assertEquals("alice", login("alice", GOOD).username(), "after five minutes (test clock) the account works again");
        assertEquals(0, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'alice'"));
        assertNull(t.text("SELECT locked_until FROM staff_users WHERE username = 'alice'"));
    }

    @Test
    void aSuccessBeforeTheLimitResetsTheCounterAndAnExpiredLockStartsAFreshCount() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertRefused("cook", "wrong-password-1");
        }
        login("cook", GOOD);
        assertRefused("cook", "wrong-password-1");
        assertEquals(1, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'cook'"), "four failures were forgotten");
        for (int i = 0; i < 4; i++) {
            assertRefused("cook", "wrong-password-1");
        }
        clock.advance(Duration.ofMinutes(6));
        assertRefused("cook", "wrong-password-1");
        assertEquals(1, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'cook'"), "an expired lock resets the count");
        assertNull(t.text("SELECT locked_until FROM staff_users WHERE username = 'cook'"));
    }

    @Test
    void lockingOneAccountDoesNotAffectAnotherOrAnExistingSession() throws Exception {
        SessionUser session = login("cook", GOOD);
        for (int i = 0; i < 5; i++) {
            assertRefused("cook", "wrong-password-1");
        }
        assertEquals("alice", login("alice", GOOD).username());
        assertTrue(auth.validateSession(session).isPresent(), "failed logins never log a victim out (SDD 12.10)");
    }

    @Test
    void anOutOfDateHashIsReplacedAtLoginAndStillVerifies() throws Exception {
        AuthService stricter = new AuthService(db.database(), t.users, new PasswordHasher(650_000), new LockoutPolicy(), clock);
        SessionUser session = stricter.authenticate("cook", GOOD.toCharArray());
        assertTrue(t.text("SELECT password_hash FROM staff_users WHERE username = 'cook'").startsWith("pbkdf2_sha256$650000$"));
        assertEquals(1, session.sessionVersion(), "the password change bumped the session version, read back for the new session");
        assertEquals("cook", stricter.authenticate("cook", GOOD.toCharArray()).username());
    }

    @Test
    void noPasswordOrHashIsEverWrittenToTheLogOrAnExceptionMessage() throws Exception {
        try {
            login("alice", "super-secret-attempt-9");
        } catch (AuthenticationException e) {
            assertFalse(e.getMessage().contains("super-secret"));
            assertFalse(String.valueOf(e.getCause()).contains("super-secret"));
        }
        assertNotEquals(GOOD, storedHash);
        assertFalse(t.text("SELECT password_hash FROM staff_users WHERE username = 'alice'").contains(GOOD));
    }

    @RepeatedTest(20)
    void concurrentWrongAttemptsCannotBypassTheFailureCounter() throws Exception {
        db.resetData();
        db.database().inTransaction(c -> t.users.insert(c, "alice", storedHash, Role.ADMIN));
        clock.set(Instant.parse("2026-10-10T12:00:00Z"));
        t.execute("UPDATE staff_users SET failed_attempts = 3"); // three more failures cross the limit of five
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "login-race"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        auth.authenticate("alice", "wrong-password-1".toCharArray());
                        return false;
                    } catch (AuthenticationException e) {
                        return true;
                    }
                }));
            }
            go.countDown();
            for (Future<Boolean> f : futures) {
                assertTrue(f.get(120, TimeUnit.SECONDS));
            }
            assertEquals(5, t.number("SELECT failed_attempts FROM staff_users WHERE username = 'alice'"),
                    "the row lock serialises attempts: the 4th and 5th count, the third found the lock and changed nothing");
            assertTrue(t.text("SELECT locked_until FROM staff_users WHERE username = 'alice'").startsWith("2026-10-10 12:05:00"));
            assertRefused("alice", GOOD);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
