package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.service.LockoutPolicy.LockoutState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LockoutPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");
    private final LockoutPolicy policy = new LockoutPolicy();

    private LockoutState failTimes(int times, Instant now) {
        LockoutState state = LockoutState.clean();
        for (int i = 0; i < times; i++) {
            state = policy.afterFailure(state, now);
        }
        return state;
    }

    @Test
    void firstFourFailuresDoNotLock() {
        for (int n = 1; n <= 4; n++) {
            LockoutState state = failTimes(n, T0);
            assertEquals(n, state.failedAttempts());
            assertNull(state.lockedUntil());
            assertFalse(policy.isLocked(state, T0));
        }
    }

    @Test
    void theFifthFailureLocksForFiveMinutes() {
        LockoutState state = failTimes(5, T0);
        assertEquals(5, state.failedAttempts());
        assertEquals(T0.plus(Duration.ofMinutes(5)), state.lockedUntil());
        assertTrue(policy.isLocked(state, T0));
        assertTrue(policy.isLocked(state, T0.plus(Duration.ofMinutes(5)).minusMillis(1)));
    }

    @Test
    void theLockEndsExactlyAtTheLockedUntilInstant() {
        LockoutState state = failTimes(5, T0);
        assertFalse(policy.isLocked(state, state.lockedUntil()));
        assertFalse(policy.isLocked(state, state.lockedUntil().plusSeconds(1)));
    }

    @Test
    void failuresWhileLockedDoNotExtendTheLock() {
        LockoutState locked = failTimes(5, T0);
        LockoutState again = policy.afterFailure(locked, T0.plusSeconds(60));
        assertEquals(locked, again);
    }

    @Test
    void anExpiredLockStartsAFreshCount() {
        LockoutState locked = failTimes(5, T0);
        Instant later = T0.plus(Duration.ofMinutes(6));
        assertEquals(LockoutState.clean(), policy.normalise(locked, later));
        LockoutState next = policy.afterFailure(locked, later);
        assertEquals(1, next.failedAttempts());
        assertNull(next.lockedUntil());
    }

    @Test
    void normaliseLeavesAnActiveLockAndAPartialCountAlone() {
        LockoutState locked = failTimes(5, T0);
        assertEquals(locked, policy.normalise(locked, T0.plusSeconds(1)));
        LockoutState partial = failTimes(3, T0);
        assertEquals(partial, policy.normalise(partial, T0.plus(Duration.ofDays(1))), "no lock, count is kept");
    }

    @Test
    void successClearsEverything() {
        assertEquals(LockoutState.clean(), policy.afterSuccess());
        assertEquals(0, policy.afterSuccess().failedAttempts());
    }

    @Test
    void limitsAreConfigurable() {
        LockoutPolicy strict = new LockoutPolicy(2, Duration.ofMinutes(10));
        LockoutState state = strict.afterFailure(strict.afterFailure(LockoutState.clean(), T0), T0);
        assertEquals(T0.plus(Duration.ofMinutes(10)), state.lockedUntil());
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LockoutPolicy(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new LockoutPolicy(5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LockoutPolicy(5, Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class, () -> new LockoutState(-1, null));
        assertThrows(NullPointerException.class, () -> policy.isLocked(null, T0));
        assertThrows(NullPointerException.class, () -> policy.afterFailure(LockoutState.clean(), null));
    }
}
