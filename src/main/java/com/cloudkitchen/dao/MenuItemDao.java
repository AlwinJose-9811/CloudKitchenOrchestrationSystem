package com.cloudkitchen.dao;

import com.cloudkitchen.domain.MenuItem;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Menu items. Implementations never commit or close the connection they are given. */
public interface MenuItemDao {

    Map<Long, MenuItem> findByIds(Connection c, Collection<Long> ids) throws SQLException;

    List<MenuItem> findByStation(Connection c, long stationId, boolean activeOnly) throws SQLException;

    List<MenuItem> findAll(Connection c) throws SQLException;

    long insert(Connection c, MenuItem item) throws SQLException;

    /** @return {@code true} if a row was changed */
    boolean update(Connection c, long id, BigDecimal price, boolean active) throws SQLException;
}
