package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.dao.StatusHistoryDao;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** JDBC access to the append-only {@code order_status_history}. */
public final class JdbcStatusHistoryDao implements StatusHistoryDao {

    @Override
    public void insert(Connection c, StatusHistoryEntry entry) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO order_status_history (order_id, from_status, to_status, changed_at, actor, note) VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setLong(1, entry.orderId());
            Sql.setNullableString(p, 2, entry.from() == null ? null : entry.from().name());
            p.setString(3, entry.to().name());
            Sql.setTime(p, 4, entry.changedAt());
            p.setString(5, entry.actor());
            Sql.setNullableString(p, 6, entry.note());
            p.executeUpdate();
        }
    }

    @Override
    public List<StatusHistoryEntry> findByOrderId(Connection c, long orderId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT order_id, from_status, to_status, changed_at, actor, note FROM order_status_history"
                        + " WHERE order_id = ? ORDER BY changed_at, id")) {
            p.setLong(1, orderId);
            try (ResultSet rs = p.executeQuery()) {
                List<StatusHistoryEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    String from = rs.getString("from_status");
                    entries.add(new StatusHistoryEntry(rs.getLong("order_id"), from == null ? null : OrderStatus.valueOf(from),
                            OrderStatus.valueOf(rs.getString("to_status")), Sql.getTime(rs, "changed_at"),
                            rs.getString("actor"), rs.getString("note")));
                }
                return entries;
            }
        }
    }
}
