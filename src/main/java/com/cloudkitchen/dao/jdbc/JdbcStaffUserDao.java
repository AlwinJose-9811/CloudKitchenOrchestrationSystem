package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.dao.StaffUserDao;
import com.cloudkitchen.domain.StaffUser;
import com.cloudkitchen.domain.enums.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** JDBC access to {@code staff_users}; never logs or returns a password hash outside {@link StaffUser}. */
public final class JdbcStaffUserDao implements StaffUserDao {

    private static final String COLUMNS = "SELECT id, username, password_hash, user_role, enabled, failed_attempts,"
            + " locked_until, session_version FROM staff_users";

    private final Clock clock;

    public JdbcStaffUserDao(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StaffUser> findByUsername(Connection c, String username) throws SQLException {
        return one(c, COLUMNS + " WHERE username = ?", username);
    }

    @Override
    public Optional<StaffUser> findByUsernameForUpdate(Connection c, String username) throws SQLException {
        return one(c, COLUMNS + " WHERE username = ? FOR UPDATE", username);
    }

    private static Optional<StaffUser> one(Connection c, String sql, String username) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, normalise(username));
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public long insert(Connection c, String username, String passwordHash, Role role) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO staff_users (username, password_hash, user_role, enabled, failed_attempts, session_version, created_at)"
                        + " VALUES (?, ?, ?, 1, 0, 0, ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, normalise(username));
            p.setString(2, passwordHash);
            p.setString(3, role.name());
            Sql.setTime(p, 4, clock.instant());
            p.executeUpdate();
            return Sql.generatedKey(p);
        }
    }

    @Override
    public void updateLoginState(Connection c, long userId, int failedAttempts, Instant lockedUntil, Instant lastLoginAt)
            throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE staff_users SET failed_attempts = ?, locked_until = ?, last_login_at = COALESCE(?, last_login_at) WHERE id = ?")) {
            p.setInt(1, failedAttempts);
            Sql.setTime(p, 2, lockedUntil);
            Sql.setTime(p, 3, lastLoginAt);
            p.setLong(4, userId);
            p.executeUpdate();
        }
    }

    @Override
    public void updatePassword(Connection c, long userId, String passwordHash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE staff_users SET password_hash = ?, session_version = session_version + 1 WHERE id = ?")) {
            p.setString(1, passwordHash);
            p.setLong(2, userId);
            p.executeUpdate();
        }
    }

    @Override
    public Optional<SessionState> findSessionState(Connection c, long userId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT enabled, user_role, session_version FROM staff_users WHERE id = ?")) {
            p.setLong(1, userId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next()
                        ? Optional.of(new SessionState(rs.getBoolean("enabled"), Role.valueOf(rs.getString("user_role")), rs.getInt("session_version")))
                        : Optional.empty();
            }
        }
    }

    @Override
    public boolean setEnabled(Connection c, long userId, boolean enabled) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE staff_users SET enabled = ?, session_version = session_version + 1 WHERE id = ?")) {
            p.setBoolean(1, enabled);
            p.setLong(2, userId);
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public long countEnabledAdmins(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT id FROM staff_users WHERE user_role = 'ADMIN' AND enabled = 1 FOR UPDATE");
             ResultSet rs = p.executeQuery()) {
            long count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        }
    }

    @Override
    public long count(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM staff_users"); ResultSet rs = p.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public List<StaffUser> findAll(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(COLUMNS + " ORDER BY id"); ResultSet rs = p.executeQuery()) {
            List<StaffUser> users = new ArrayList<>();
            while (rs.next()) {
                users.add(map(rs));
            }
            return users;
        }
    }

    private static String normalise(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private static StaffUser map(ResultSet rs) throws SQLException {
        return new StaffUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                Role.valueOf(rs.getString("user_role")), rs.getBoolean("enabled"), rs.getInt("failed_attempts"),
                Sql.getTime(rs, "locked_until"), rs.getInt("session_version"));
    }
}
