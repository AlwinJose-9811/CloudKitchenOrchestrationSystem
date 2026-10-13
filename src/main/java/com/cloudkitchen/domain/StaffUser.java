package com.cloudkitchen.domain;

import com.cloudkitchen.domain.enums.Role;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A login account with its lockout state and its session revocation counter. The password hash is never printed:
 * {@link #toString()} leaves it out.
 */
public final class StaffUser {

    public static final int MAX_USERNAME_LENGTH = 50;
    public static final int MAX_HASH_LENGTH = 255;

    private final long id;
    private final String username;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;
    private final int failedAttempts;
    private final Instant lockedUntil;
    private final int sessionVersion;

    public StaffUser(long id, String username, String passwordHash, Role role, boolean enabled, int failedAttempts,
                     Instant lockedUntil, int sessionVersion) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (username == null || username.isBlank() || username.strip().length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("username must be 1-" + MAX_USERNAME_LENGTH + " characters");
        }
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > MAX_HASH_LENGTH) {
            throw new IllegalArgumentException("passwordHash must be 1-" + MAX_HASH_LENGTH + " characters");
        }
        Objects.requireNonNull(role, "role");
        if (failedAttempts < 0 || sessionVersion < 0) {
            throw new IllegalArgumentException("failedAttempts and sessionVersion must not be negative");
        }
        this.id = id;
        this.username = username.strip().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.sessionVersion = sessionVersion;
    }

    public long getId() {
        return id;
    }

    /** Stored in lower case. */
    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    /** {@code null} when the account is not locked. */
    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StaffUser that && id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "StaffUser[id=" + id + ", username=" + username + ", role=" + role + ", enabled=" + enabled + "]";
    }
}
