package com.cloudkitchen.dao.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dao.StaffUserDao.SessionState;
import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.domain.StaffUser;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffUserDaoTest {

    private static final String HASH = "pbkdf2_sha256$600000$c2FsdHNhbHRzYWx0$aGFzaGhhc2g=";

    private FakeClock clock;
    private DbFixture db;
    private DbTestSupport t;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
        t = new DbTestSupport(db);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
    }

    private long add(String name, Role role) throws Exception {
        return db.database().inTransaction(c -> t.users.insert(c, name, HASH, role));
    }

    @Test
    void aCreatedUserIsReadBackWithCleanLockoutState() throws Exception {
        long id = add("  Alice ", Role.ADMIN);
        StaffUser user = db.database().withConnection(c -> t.users.findByUsername(c, "ALICE")).orElseThrow();
        assertEquals(id, user.getId());
        assertEquals("alice", user.getUsername(), "stored and matched lower-case");
        assertEquals(HASH, user.getPasswordHash());
        assertEquals(Role.ADMIN, user.getRole());
        assertTrue(user.isEnabled());
        assertEquals(0, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
        assertEquals(0, user.getSessionVersion());
        assertTrue(db.database().withConnection(c -> t.users.findByUsername(c, "nobody")).isEmpty());
    }

    @Test
    void usernamesAreUniqueIgnoringCase() throws Exception {
        add("bob", Role.KITCHEN_STAFF);
        PersistenceException e = assertThrows(PersistenceException.class, () -> add("BOB", Role.ADMIN));
        assertTrue(SqlErrors.isDuplicateKey(e));
        assertEquals(1, t.num(c -> t.users.count(c)));
    }

    @Test
    void lockoutColumnsAreWrittenAndReadInUtc() throws Exception {
        long id = add("carol", Role.KITCHEN_STAFF);
        Instant until = Instant.parse("2026-10-10T12:05:00.250Z");
        Instant login = Instant.parse("2026-10-10T12:00:01Z");
        db.database().inTransaction(c -> {
            t.users.updateLoginState(c, id, 5, until, null);
            return null;
        });
        StaffUser locked = db.database().withConnection(c -> t.users.findByUsername(c, "carol")).orElseThrow();
        assertEquals(5, locked.getFailedAttempts());
        assertEquals(until, locked.getLockedUntil());
        assertNull(t.text("SELECT last_login_at FROM staff_users WHERE id = ?", id), "a null login time leaves last_login_at alone");
        db.database().inTransaction(c -> {
            t.users.updateLoginState(c, id, 0, null, login);
            return null;
        });
        StaffUser cleared = db.database().withConnection(c -> t.users.findByUsername(c, "carol")).orElseThrow();
        assertEquals(0, cleared.getFailedAttempts());
        assertNull(cleared.getLockedUntil());
        assertTrue(t.text("SELECT last_login_at FROM staff_users WHERE id = ?", id).startsWith("2026-10-10 12:00:01"));
    }

    @Test
    void sessionStateFollowsEnableDisableAndPasswordChanges() throws Exception {
        long id = add("dave", Role.ADMIN);
        assertEquals(new SessionState(true, Role.ADMIN, 0), db.database().withConnection(c -> t.users.findSessionState(c, id)).orElseThrow());
        assertTrue(t.ok(c -> t.users.setEnabled(c, id, false)));
        assertEquals(new SessionState(false, Role.ADMIN, 1), db.database().withConnection(c -> t.users.findSessionState(c, id)).orElseThrow());
        assertTrue(t.ok(c -> t.users.setEnabled(c, id, true)));
        assertEquals(2, db.database().withConnection(c -> t.users.findSessionState(c, id)).orElseThrow().sessionVersion(), "re-enabling does not revive old sessions");
        db.database().inTransaction(c -> {
            t.users.updatePassword(c, id, "pbkdf2_sha256$600000$bmV3c2FsdA==$bmV3aGFzaA==");
            return null;
        });
        assertEquals(3, db.database().withConnection(c -> t.users.findSessionState(c, id)).orElseThrow().sessionVersion());
        assertTrue(db.database().withConnection(c -> t.users.findSessionState(c, 9_999_999L)).isEmpty());
        assertFalse(t.ok(c -> t.users.setEnabled(c, 9_999_999L, false)));
    }

    @Test
    void enabledAdminsAreCountedAndLocked() throws Exception {
        add("root1", Role.ADMIN);
        long second = add("root2", Role.ADMIN);
        add("cook", Role.KITCHEN_STAFF);
        assertEquals(2, t.num(c -> t.users.countEnabledAdmins(c)));
        db.database().inTransaction(c -> t.users.setEnabled(c, second, false));
        assertEquals(1, t.num(c -> t.users.countEnabledAdmins(c)));
        assertEquals(3, db.database().withConnection(c -> t.users.findAll(c)).size());
    }

    @Test
    void selectForUpdateSerialisesConcurrentLoginAttempts() throws Exception {
        long id = add("erin", Role.KITCHEN_STAFF);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                db.database().inTransaction(c -> {
                    t.users.findByUsernameForUpdate(c, "erin");
                    holding.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    t.users.updateLoginState(c, id, 1, null, null);
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, r -> new Thread(r, "login-one").start());
        assertTrue(holding.await(20, TimeUnit.SECONDS));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
            try {
                return db.database().inTransaction(c -> {
                    StaffUser seen = t.users.findByUsernameForUpdate(c, "erin").orElseThrow();
                    t.users.updateLoginState(c, id, seen.getFailedAttempts() + 1, null, null);
                    return seen.getFailedAttempts();
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, r -> new Thread(r, "login-two").start());
        Thread.sleep(300);
        assertFalse(second.isDone(), "the second attempt waits for the row lock");
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
        assertEquals(1, second.get(30, TimeUnit.SECONDS), "it then sees the first attempt's committed count");
        assertEquals(2, db.database().withConnection(c -> t.users.findByUsername(c, "erin")).orElseThrow().getFailedAttempts());
    }

    @Test
    void theRoleColumnIsChecked() {
        assertThrows(SQLException.class, () -> t.execute(
                "INSERT INTO staff_users (username, password_hash, user_role, created_at) VALUES ('mallory', 'x', 'ROOT', UTC_TIMESTAMP(3))"));
        assertEquals(List.of(), List.of());
    }
}
