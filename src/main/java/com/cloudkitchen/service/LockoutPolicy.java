package com.cloudkitchen.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure account-lockout rules (SDD section 12.2): the 5th consecutive failure locks the account for 5 minutes, and an
 * expired lock starts a fresh count. No clock and no storage: callers pass the state and the current time.
 */
public final class LockoutPolicy {

    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    public static final Duration DEFAULT_LOCK = Duration.ofMinutes(5);

    /** What is stored for one account. {@code lockedUntil} is {@code null} when not locked. */
    public record LockoutState(int failedAttempts, Instant lockedUntil) {
        public LockoutState {
            if (failedAttempts < 0) {
                throw new IllegalArgumentException("failedAttempts must not be negative");
            }
        }

        public static LockoutState clean() {
            return new LockoutState(0, null);
        }
    }

    private final int maxAttempts;
    private final Duration lockDuration;

    public LockoutPolicy() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_LOCK);
    }

    public LockoutPolicy(int maxAttempts, Duration lockDuration) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("lockDuration must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
    }

    /** Locked while {@code now} is before {@code lockedUntil}; the lock ends exactly at that instant. */
    public boolean isLocked(LockoutState state, Instant now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        return state.lockedUntil() != null && now.isBefore(state.lockedUntil());
    }

    /** Counts one more failure; reaching the limit sets the lock. A still-valid lock is left as it is. */
    public LockoutState afterFailure(LockoutState state, Instant now) {
        LockoutState current = normalise(state, now);
        if (current.lockedUntil() != null) {
            return current;
        }
        int attempts = current.failedAttempts() + 1;
        return attempts >= maxAttempts ? new LockoutState(attempts, now.plus(lockDuration)) : new LockoutState(attempts, null);
    }

    public LockoutState afterSuccess() {
        return LockoutState.clean();
    }

    /** An expired lock resets the counter; anything else is returned unchanged. */
    public LockoutState normalise(LockoutState state, Instant now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        if (state.lockedUntil() != null && !now.isBefore(state.lockedUntil())) {
            return LockoutState.clean();
        }
        return state;
    }
}
