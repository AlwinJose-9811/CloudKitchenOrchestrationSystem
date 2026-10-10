package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.dao.MenuItemDao;
import com.cloudkitchen.domain.MenuItem;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** JDBC access to {@code menu_items}. */
public final class JdbcMenuItemDao implements MenuItemDao {

    private static final String COLUMNS = "SELECT id, station_id, name, price, active FROM menu_items";

    private final Clock clock;

    public JdbcMenuItemDao(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Map<Long, MenuItem> findByIds(Connection c, Collection<Long> ids) throws SQLException {
        Map<Long, MenuItem> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        List<Long> sorted = new ArrayList<>(new TreeSet<>(ids));
        try (PreparedStatement p = c.prepareStatement(COLUMNS + " WHERE id IN (" + Sql.placeholders(sorted.size()) + ")")) {
            for (int i = 0; i < sorted.size(); i++) {
                p.setLong(i + 1, sorted.get(i));
            }
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    MenuItem item = map(rs);
                    found.put(item.getId(), item);
                }
            }
        }
        return found;
    }

    @Override
    public List<MenuItem> findByStation(Connection c, long stationId, boolean activeOnly) throws SQLException {
        String sql = activeOnly ? COLUMNS + " WHERE station_id = ? AND active = 1 ORDER BY id"
                : COLUMNS + " WHERE station_id = ? ORDER BY id";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, stationId);
            return list(p);
        }
    }

    @Override
    public List<MenuItem> findAll(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(COLUMNS + " ORDER BY id")) {
            return list(p);
        }
    }

    @Override
    public long insert(Connection c, long stationId, String name, BigDecimal price, boolean active) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO menu_items (station_id, name, price, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, stationId);
            p.setString(2, name);
            p.setBigDecimal(3, price);
            p.setBoolean(4, active);
            Sql.setTime(p, 5, clock.instant());
            Sql.setTime(p, 6, clock.instant());
            p.executeUpdate();
            return Sql.generatedKey(p);
        }
    }

    @Override
    public boolean update(Connection c, long id, BigDecimal price, boolean active) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE menu_items SET price = ?, active = ?, updated_at = ? WHERE id = ?")) {
            p.setBigDecimal(1, price);
            p.setBoolean(2, active);
            Sql.setTime(p, 3, clock.instant());
            p.setLong(4, id);
            return p.executeUpdate() == 1;
        }
    }

    private static List<MenuItem> list(PreparedStatement p) throws SQLException {
        try (ResultSet rs = p.executeQuery()) {
            List<MenuItem> items = new ArrayList<>();
            while (rs.next()) {
                items.add(map(rs));
            }
            return items;
        }
    }

    private static MenuItem map(ResultSet rs) throws SQLException {
        return new MenuItem(rs.getLong("id"), rs.getLong("station_id"), rs.getString("name"), rs.getBigDecimal("price"), rs.getBoolean("active"));
    }
}
