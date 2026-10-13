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

    /**
     * Inserts a new item and returns its generated id. (A {@link MenuItem} always has an id, so a new row is described
     * by its fields; the inventory row is created separately by {@link StockDao#create}.)
     */
    long insert(Connection c, long stationId, String name, BigDecimal price, boolean active) throws SQLException;

    /** @return {@code true} if a row was changed */
    boolean update(Connection c, long id, BigDecimal price, boolean active) throws SQLException;
}
