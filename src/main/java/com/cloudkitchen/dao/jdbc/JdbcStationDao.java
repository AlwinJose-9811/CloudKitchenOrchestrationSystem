package com.cloudkitchen.dao.jdbc;

import com.cloudkitchen.dao.StationDao;
import com.cloudkitchen.domain.Station;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC access to {@code stations}. */
public final class JdbcStationDao implements StationDao {

    private static final String COLUMNS = "SELECT id, code, brand_name, active FROM stations";

    @Override
    public List<Station> findAllActive(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(COLUMNS + " WHERE active = 1 ORDER BY id");
             ResultSet rs = p.executeQuery()) {
            List<Station> stations = new ArrayList<>();
            while (rs.next()) {
                stations.add(map(rs));
            }
            return stations;
        }
    }

    @Override
    public Optional<Station> findByCode(Connection c, String code) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(COLUMNS + " WHERE code = ?")) {
            p.setString(1, code);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Station> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Station map(ResultSet rs) throws SQLException {
        return new Station(rs.getLong("id"), rs.getString("code"), rs.getString("brand_name"), rs.getBoolean("active"));
    }
}
