package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.dao.StockDao;
import com.cloudkitchen.domain.StockLevel;
import com.cloudkitchen.dto.StockRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC access to {@code inventory}; the conditional deduction is the single statement of FR-INV-02. */
public final class JdbcStockDao implements StockDao {

    private static final String ROWS = "SELECT i.item_id, m.name, s.code, i.stock_qty, i.reorder_level FROM inventory i"
            + " JOIN menu_items m ON m.id = i.item_id JOIN stations s ON s.id = m.station_id";

    private final Clock clock;

    public JdbcStockDao(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean deduct(Connection c, long itemId, int qty) throws SQLException {
        requirePositive(qty);
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE inventory SET stock_qty = stock_qty - ?, updated_at = ? WHERE item_id = ? AND stock_qty >= ?")) {
            p.setInt(1, qty);
            Sql.setTime(p, 2, clock.instant());
            p.setLong(3, itemId);
            p.setInt(4, qty);
            return p.executeUpdate() == 1;
        }
    }

    @Override
    public void restore(Connection c, long itemId, int qty) throws SQLException {
        requirePositive(qty);
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE inventory SET stock_qty = stock_qty + ?, updated_at = ? WHERE item_id = ?")) {
            p.setInt(1, qty);
            Sql.setTime(p, 2, clock.instant());
            p.setLong(3, itemId);
            if (p.executeUpdate() != 1) {
                throw new SQLException("no inventory row for item " + itemId, "02000");
            }
        }
    }

    @Override
    public List<StockRow> findAll(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(ROWS + " ORDER BY s.code, i.item_id")) {
            return rows(p);
        }
    }

    @Override
    public List<StockRow> findLow(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(ROWS + " WHERE i.stock_qty <= i.reorder_level ORDER BY s.code, i.item_id")) {
            return rows(p);
        }
    }

    @Override
    public void set(Connection c, long itemId, int stockQty, int reorderLevel) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE inventory SET stock_qty = ?, reorder_level = ?, updated_at = ? WHERE item_id = ?")) {
            p.setInt(1, stockQty);
            p.setInt(2, reorderLevel);
            Sql.setTime(p, 3, clock.instant());
            p.setLong(4, itemId);
            if (p.executeUpdate() != 1) {
                throw new SQLException("no inventory row for item " + itemId, "02000");
            }
        }
    }

    @Override
    public void create(Connection c, long itemId, int stockQty, int reorderLevel) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO inventory (item_id, stock_qty, reorder_level, updated_at) VALUES (?, ?, ?, ?)")) {
            p.setLong(1, itemId);
            p.setInt(2, stockQty);
            p.setInt(3, reorderLevel);
            Sql.setTime(p, 4, clock.instant());
            p.executeUpdate();
        }
    }

    @Override
    public Optional<StockLevel> find(Connection c, long itemId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT item_id, stock_qty, reorder_level FROM inventory WHERE item_id = ?")) {
            p.setLong(1, itemId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next()
                        ? Optional.of(new StockLevel(rs.getLong("item_id"), rs.getInt("stock_qty"), rs.getInt("reorder_level")))
                        : Optional.empty();
            }
        }
    }

    private static List<StockRow> rows(PreparedStatement p) throws SQLException {
        try (ResultSet rs = p.executeQuery()) {
            List<StockRow> rows = new ArrayList<>();
            while (rs.next()) {
                int qty = rs.getInt("stock_qty");
                int reorder = rs.getInt("reorder_level");
                rows.add(new StockRow(rs.getLong("item_id"), rs.getString("name"), rs.getString("code"), qty, reorder, qty <= reorder));
            }
            return rows;
        }
    }

    private static void requirePositive(int qty) {
        if (qty < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
