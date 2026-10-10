package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.util.TimeUtil;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;

/** Small JDBC helpers shared by the DAOs: UTC timestamps (FR-ENV-06) and placeholder lists. */
final class Sql {

    private Sql() {
    }

    /** Binds an instant as a UTC {@code DATETIME(3)} value; {@code null} binds SQL NULL. */
    static void setTime(PreparedStatement p, int index, Instant value) throws SQLException {
        if (value == null) {
            p.setNull(index, Types.TIMESTAMP);
        } else {
            p.setObject(index, TimeUtil.toUtcLocal(value));
        }
    }

    static Instant getTime(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : TimeUtil.fromUtcLocal(value);
    }

    /** {@code ?,?,?} for an {@code IN} list: only question marks are ever produced, values are always bound. */
    static String placeholders(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("an IN list needs at least one value");
        }
        return "?,".repeat(count - 1) + "?";
    }

    static void setNullableString(PreparedStatement p, int index, String value) throws SQLException {
        if (value == null) {
            p.setNull(index, Types.VARCHAR);
        } else {
            p.setString(index, value);
        }
    }

    static long generatedKey(PreparedStatement p) throws SQLException {
        try (ResultSet keys = p.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("the insert returned no generated key", "HY000");
            }
            return keys.getLong(1);
        }
    }
}
