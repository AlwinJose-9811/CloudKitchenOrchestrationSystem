package com.cloudkitchen.service;

import com.cloudkitchen.dao.StaffUserDao;
import com.cloudkitchen.dao.StaffUserDao.SessionState;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.domain.StaffUser;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.LockoutPolicy.LockoutState;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Login, lockout and per-request account checks (SDD 12.1, 12.2, 12.10). Every login failure looks the same to the
 * caller (unknown user, wrong password, locked or disabled account: the generic {@link AuthenticationException}), and
 * the password hash is verified in every case so the timing does not tell the cases apart (NFR-SEC-03).
 */
public final class AuthService {

    private final Database database;
    private final StaffUserDao users;
    private final PasswordHasher hasher;
    private final LockoutPolicy lockout;
    private final Clock clock;
    private volatile String dummyHash;

    public AuthService(Database database, StaffUserDao users, PasswordHasher hasher, LockoutPolicy lockout, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.users = Objects.requireNonNull(users, "users");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.lockout = Objects.requireNonNull(lockout, "lockout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Outcome of the login transaction; a failure is turned into the exception after the commit so counters persist. */
    private record Attempt(SessionUser user) {
        static final Attempt FAILED = new Attempt(null);
    }

    /**
     * Checks the credentials in one transaction (TX7) that locks the user row, so concurrent attempts cannot bypass the
     * failure counter. The password array is cleared before returning.
     *
     * @throws AuthenticationException always with the generic message, for every kind of refusal
     * @throws CloudKitchenException   if the database fails
     */
    public SessionUser authenticate(String username, char[] password) throws CloudKitchenException {
        try {
            Attempt attempt = database.inTransaction(c -> {
                Optional<StaffUser> found = users.findByUsernameForUpdate(c, username);
                if (found.isEmpty()) {
                    hasher.verify(password, dummyHash());
                    return Attempt.FAILED;
                }
                StaffUser user = found.get();
                Instant now = clock.instant();
                boolean matches = hasher.verify(password, user.getPasswordHash());
                LockoutState state = lockout.normalise(new LockoutState(user.getFailedAttempts(), user.getLockedUntil()), now);
                if (lockout.isLocked(state, now) || !user.isEnabled()) {
                    return Attempt.FAILED;
                }
                if (!matches) {
                    LockoutState next = lockout.afterFailure(state, now);
                    users.updateLoginState(c, user.getId(), next.failedAttempts(), next.lockedUntil(), null);
                    return Attempt.FAILED;
                }
                LockoutState clean = lockout.afterSuccess();
                users.updateLoginState(c, user.getId(), clean.failedAttempts(), clean.lockedUntil(), now);
                if (hasher.needsRehash(user.getPasswordHash())) {
                    users.updatePassword(c, user.getId(), hasher.hash(password));
                }
                SessionState session = users.findSessionState(c, user.getId()).orElseThrow();
                return new Attempt(new SessionUser(user.getId(), user.getUsername(), user.getRole(), session.sessionVersion()));
            });
            if (attempt.user() == null) {
                throw new AuthenticationException();
            }
            return attempt.user();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = hasher.hash("not-a-real-password".toCharArray());
            dummyHash = hash;
        }
        return hash;
    }

    /**
     * The per-request check of SDD 12.10: one primary-key lookup, no cache. Empty when the user is gone, disabled or
     * the session version differs; otherwise the user with the <em>current</em> role.
     */
    public Optional<SessionUser> validateSession(SessionUser session) throws CloudKitchenException {
        if (session == null) {
            return Optional.empty();
        }
        Optional<SessionState> state = database.withConnection(c -> users.findSessionState(c, session.userId()));
        if (state.isEmpty() || !state.get().enabled() || state.get().sessionVersion() != session.sessionVersion()) {
            return Optional.empty();
        }
        return Optional.of(new SessionUser(session.userId(), session.username(), state.get().role(), state.get().sessionVersion()));
    }

    /**
     * TX10: enables or disables an account and bumps its session version in one transaction. Refuses to disable the
     * acting admin and refuses to disable the last enabled administrator.
     */
    public void setEnabled(long actorUserId, long targetUserId, boolean enabled) throws CloudKitchenException {
        database.inTransaction(c -> {
            Optional<SessionState> target = users.findSessionState(c, targetUserId);
            if (target.isEmpty()) {
                throw new CloudKitchenException("USER_NOT_FOUND", "No such account.");
            }
            if (!enabled) {
                if (actorUserId == targetUserId) {
                    throw new CloudKitchenException("SELF_DISABLE_REFUSED", "You cannot disable your own account.");
                }
                long admins = users.countEnabledAdmins(c); // locks the enabled admin rows for the guard
                if (target.get().role() == Role.ADMIN && target.get().enabled() && admins <= 1) {
                    throw new CloudKitchenException("LAST_ADMIN_REFUSED", "The last enabled administrator cannot be disabled.");
                }
            }
            users.setEnabled(c, targetUserId, enabled);
            return null;
        });
    }
}
