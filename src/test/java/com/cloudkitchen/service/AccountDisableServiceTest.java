package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dao.StaffUserDao;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import com.cloudkitchen.testsupport.FaultInjector;
import java.sql.SQLTransientConnectionException;
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

/** SDD 12.10 / D-033: a disabled or demoted account stops working on its next request. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDisableServiceTest {

    private static final String GOOD = "correct-horse-battery-1";

    private FakeClock clock;
    private DbFixture db;
    private DbTestSupport t;
    private PasswordHasher hasher;
    private FaultInjector<StaffUserDao> faults;
    private AuthService auth;
    private String storedHash;
    private long alice;
    private long bob;
    private long cook;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
        t = new DbTestSupport(db);
        hasher = new PasswordHasher();
        storedHash = hasher.hash(GOOD.toCharArray());
        faults = FaultInjector.wrap(StaffUserDao.class, t.users);
        auth = new AuthService(db.database(), faults.dao(), hasher, new LockoutPolicy(), clock);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
        faults.clearFaults();
        faults.resetCounters();
        alice = db.database().inTransaction(c -> t.users.insert(c, "alice", storedHash, Role.ADMIN));
        bob = db.database().inTransaction(c -> t.users.insert(c, "bob", storedHash, Role.ADMIN));
        cook = db.database().inTransaction(c -> t.users.insert(c, "cook", storedHash, Role.KITCHEN_STAFF));
    }

    private SessionUser session(long id, String name, Role role) {
        return new SessionUser(id, name, role, 0);
    }

    @Test
    void disablingBumpsTheVersionAndInvalidatesTheOldSessionAtOnce() throws Exception {
        SessionUser old = session(cook, "cook", Role.KITCHEN_STAFF);
        assertTrue(auth.validateSession(old).isPresent());
        auth.setEnabled(alice, cook, false);
        assertEquals(1, t.number("SELECT session_version FROM staff_users WHERE id = ?", cook));
        assertEquals(0, t.number("SELECT enabled FROM staff_users WHERE id = ?", cook));
        assertTrue(auth.validateSession(old).isEmpty(), "the very next request is rejected");
    }

    @Test
    void reEnablingDoesNotReviveOldSessionsButAFreshLoginWorks() throws Exception {
        SessionUser old = session(cook, "cook", Role.KITCHEN_STAFF);
        auth.setEnabled(alice, cook, false);
        auth.setEnabled(alice, cook, true);
        assertEquals(2, t.number("SELECT session_version FROM staff_users WHERE id = ?", cook));
        assertTrue(auth.validateSession(old).isEmpty());
        SessionUser fresh = auth.authenticate("cook", GOOD.toCharArray());
        assertEquals(2, fresh.sessionVersion());
        assertTrue(auth.validateSession(fresh).isPresent());
    }

    @Test
    void aDisabledAccountCannotLogInWithTheGenericMessage() throws Exception {
        auth.setEnabled(alice, cook, false);
        AuthenticationException e = assertThrows(AuthenticationException.class, () -> auth.authenticate("cook", GOOD.toCharArray()));
        assertEquals(AuthenticationException.GENERIC_MESSAGE, e.getMessage());
        assertEquals(0, t.number("SELECT failed_attempts FROM staff_users WHERE id = ?", cook), "a disabled login is not a failed attempt");
    }

    @Test
    void validateSessionRejectsUnknownUsersAndStaleVersionsAndReturnsTheCurrentRole() throws Exception {
        assertTrue(auth.validateSession(session(999_999L, "ghost", Role.ADMIN)).isEmpty());
        assertTrue(auth.validateSession(null).isEmpty());
        assertTrue(auth.validateSession(new SessionUser(cook, "cook", Role.KITCHEN_STAFF, 5)).isEmpty(), "version mismatch");
        t.execute("UPDATE staff_users SET user_role = 'ADMIN' WHERE id = ?", cook);
        SessionUser current = auth.validateSession(session(cook, "cook", Role.KITCHEN_STAFF)).orElseThrow();
        assertEquals(Role.ADMIN, current.role(), "role changes apply on the next request");
    }

    @Test
    void youCannotDisableYourselfOrAnUnknownAccount() throws Exception {
        CloudKitchenException self = assertThrows(CloudKitchenException.class, () -> auth.setEnabled(alice, alice, false));
        assertEquals("SELF_DISABLE_REFUSED", self.getCode());
        CloudKitchenException unknown = assertThrows(CloudKitchenException.class, () -> auth.setEnabled(alice, 999_999L, false));
        assertEquals("USER_NOT_FOUND", unknown.getCode());
        assertEquals(1, t.number("SELECT enabled FROM staff_users WHERE id = ?", alice));
        assertEquals(0, t.number("SELECT session_version FROM staff_users WHERE id = ?", alice), "a refused operation changes nothing");
    }

    @Test
    void theLastEnabledAdministratorCannotBeDisabledButAnotherAdminCan() throws Exception {
        auth.setEnabled(alice, bob, false);
        CloudKitchenException last = assertThrows(CloudKitchenException.class, () -> auth.setEnabled(cook, alice, false));
        assertEquals("LAST_ADMIN_REFUSED", last.getCode());
        assertEquals(1, t.number("SELECT enabled FROM staff_users WHERE id = ?", alice));
        auth.setEnabled(alice, cook, false); // a non-admin is never "the last admin"
        auth.setEnabled(alice, bob, true);
        auth.setEnabled(bob, alice, false); // now two admins exist
        assertEquals(0, t.number("SELECT enabled FROM staff_users WHERE id = ?", alice));
    }

    @Test
    void aFaultAfterTheUpdateRollsBackBothTheFlagAndTheVersion() throws Exception {
        faults.failAfter("setEnabled", 1, new SQLTransientConnectionException("injected"));
        assertThrows(CloudKitchenException.class, () -> auth.setEnabled(alice, cook, false));
        assertEquals(1, t.number("SELECT enabled FROM staff_users WHERE id = ?", cook));
        assertEquals(0, t.number("SELECT session_version FROM staff_users WHERE id = ?", cook), "never 'disabled but sessions still valid'");
        faults.clearFaults();
        auth.setEnabled(alice, cook, false);
        assertEquals(0, t.number("SELECT enabled FROM staff_users WHERE id = ?", cook));
    }

    @RepeatedTest(20)
    void twoAdminsDisablingEachOtherAtTheSameTimeLeaveExactlyOneEnabled() throws Exception {
        db.resetData();
        long a = db.database().inTransaction(c -> t.users.insert(c, "alice", storedHash, Role.ADMIN));
        long b = db.database().inTransaction(c -> t.users.insert(c, "bob", storedHash, Role.ADMIN));
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "disable-race"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            results.add(pool.submit(() -> attempt(go, a, b)));
            results.add(pool.submit(() -> attempt(go, b, a)));
            go.countDown();
            int succeeded = 0;
            for (Future<Boolean> f : results) {
                if (f.get(60, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded, "the guard query locks the admin rows, so the second decision sees the first");
            assertEquals(1, t.number("SELECT COUNT(*) FROM staff_users WHERE user_role = 'ADMIN' AND enabled = 1"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean attempt(CountDownLatch go, long actor, long target) throws Exception {
        go.await();
        try {
            auth.setEnabled(actor, target, false);
            return true;
        } catch (CloudKitchenException e) {
            assertFalse(e.getCode().isBlank());
            return false;
        }
    }
}
