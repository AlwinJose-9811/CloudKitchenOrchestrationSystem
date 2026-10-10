package com.cloudkitchen.dao;

import com.cloudkitchen.domain.StaffUser;
import com.cloudkitchen.domain.enums.Role;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Staff accounts. Implementations never commit or close the connection they are given. */
public interface StaffUserDao {

    /** What the per-request account check needs (SDD 12.10). */
    record SessionState(boolean enabled, Role role, int sessionVersion) {
    }

    Optional<StaffUser> findByUsername(Connection c, String username) throws SQLException;

    /** Locks the row ({@code SELECT ... FOR UPDATE}) so concurrent logins cannot bypass the failure counter. */
    Optional<StaffUser> findByUsernameForUpdate(Connection c, String username) throws SQLException;

    long insert(Connection c, StaffUser user) throws SQLException;

    void updateLoginState(Connection c, long userId, int failedAttempts, Instant lockedUntil, Instant lastLoginAt)
            throws SQLException;

    /** Stores the new hash and bumps {@code session_version}. */
    void updatePassword(Connection c, long userId, String passwordHash) throws SQLException;

    Optional<SessionState> findSessionState(Connection c, long userId) throws SQLException;

    /** Sets the flag and bumps {@code session_version}. */
    boolean setEnabled(Connection c, long userId, boolean enabled) throws SQLException;

    /** Counts enabled admins and locks them ({@code FOR UPDATE}). */
    long countEnabledAdmins(Connection c) throws SQLException;

    long count(Connection c) throws SQLException;

    List<StaffUser> findAll(Connection c) throws SQLException;
}
