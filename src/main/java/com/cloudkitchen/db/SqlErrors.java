package com.cloudkitchen.db;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;

/**
 * Classifies database errors by the signals the server really sends (Phase 1 findings S-09, S-10): a deadlock is
 * vendor code 1213, and a lock wait timeout (1205) also carries SQLState 40001, so the state alone is not enough.
 */
public final class SqlErrors {

    public static final int DEADLOCK = 1213;
    public static final int LOCK_WAIT_TIMEOUT = 1205;
    public static final int DUPLICATE_KEY = 1062;

    private SqlErrors() {
    }

    /** True for a deadlock victim: vendor code 1213, or SQLState 40001 that is not a lock wait timeout. */
    public static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException e) {
                for (SQLException x = e; x != null; x = x.getNextException()) {
                    if (x.getErrorCode() == DEADLOCK
                            || ("40001".equals(x.getSQLState()) && x.getErrorCode() != LOCK_WAIT_TIMEOUT)) {
                        return true;
                    }
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** True for a unique-key violation (SQLState 23000, vendor code 1062), also when wrapped. */
    public static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException e && "23000".equals(e.getSQLState()) && e.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** True when the server cannot be reached or the pool timed out: connection-class SQLState 08, or a transient connection failure. */
    public static boolean isUnavailable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException || t instanceof java.sql.SQLNonTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException e && e.getSQLState() != null && e.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * True if retrying the same step later may succeed (RR-8): a persistence failure (or raw SQL error)
     * caused by a transient, recoverable or rollback-class SQL error, a connection-class SQLState or a deadlock.
     * Anything else, for example a constraint violation, is permanent.
     */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientException || t instanceof SQLRecoverableException
                    || t instanceof SQLTransactionRollbackException) {
                return true;
            }
            if (t instanceof SQLException e && e.getSQLState() != null && e.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return isDeadlock(error);
    }
}
