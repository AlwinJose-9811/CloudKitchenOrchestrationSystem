package com.cloudkitchen.db;

import java.sql.SQLException;

/**
 * Raised inside a DAO when a lease statement or its transaction slipped past the guard budget (SDD 7.11.15, S-5).
 * It is an {@link SQLException} so DAO methods keep their signature; {@link Database#inTransaction} rolls back and
 * turns it into a {@code LeaseStampStaleException}.
 */
public final class StaleStampSqlException extends SQLException {

    private static final long serialVersionUID = 1L;

    public StaleStampSqlException(String message) {
        super(message, "HY000");
    }
}
