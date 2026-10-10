package com.cloudkitchen.db;

import com.cloudkitchen.exception.CloudKitchenException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A unit of database work run by {@link Database}. The body must touch only the database and local variables:
 * metrics, log events and queue operations belong after the transaction (TXR-4), because a deadlock re-runs the
 * whole body.
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface TxWork<T> {

    T run(Connection c) throws SQLException, CloudKitchenException;
}
