package com.cloudkitchen.dao;

import com.cloudkitchen.domain.StockLevel;
import com.cloudkitchen.dto.StockRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Item-level stock. Implementations never commit or close the connection they are given. */
public interface StockDao {

    /** Conditional {@code UPDATE ... WHERE stock_qty >= qty}; {@code true} if and only if exactly one row changed. */
    boolean deduct(Connection c, long itemId, int qty) throws SQLException;

    void restore(Connection c, long itemId, int qty) throws SQLException;

    List<StockRow> findAll(Connection c) throws SQLException;

    List<StockRow> findLow(Connection c) throws SQLException;

    void set(Connection c, long itemId, int stockQty, int reorderLevel) throws SQLException;

    /** Creates the inventory row of a new menu item. */
    void create(Connection c, long itemId, int stockQty, int reorderLevel) throws SQLException;

    Optional<StockLevel> find(Connection c, long itemId) throws SQLException;
}
