package com.cloudkitchen.dao;

import com.cloudkitchen.domain.StatusHistoryEntry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** The append-only status history of orders. Implementations never commit or close the connection they are given. */
public interface StatusHistoryDao {

    void insert(Connection c, StatusHistoryEntry entry) throws SQLException;

    /** Ordered by change time, then id. */
    List<StatusHistoryEntry> findByOrderId(Connection c, long orderId) throws SQLException;
}
