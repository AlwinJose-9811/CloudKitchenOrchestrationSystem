package com.cloudkitchen.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.PersistenceException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

class SqlErrorsTest {

    private static final SQLException DEADLOCK = new SQLTransactionRollbackException("deadlock", "40001", 1213);
    private static final SQLException LOCK_WAIT = new SQLTransactionRollbackException("lock wait timeout", "40001", 1205);

    @Test
    void deadlockIsRecognisedByVendorCodeAndStateButNotTheLockWaitTimeout() {
        assertTrue(SqlErrors.isDeadlock(DEADLOCK));
        assertTrue(SqlErrors.isDeadlock(new SQLException("x", "40001", 0)), "SQLState 40001 without a vendor code");
        assertTrue(SqlErrors.isDeadlock(new SQLException("x", "HY000", 1213)));
        assertFalse(SqlErrors.isDeadlock(LOCK_WAIT), "Phase 1 finding: 1205 also has SQLState 40001");
        assertFalse(SqlErrors.isDeadlock(new SQLException("x", "23000", 1062)));
        assertFalse(SqlErrors.isDeadlock(new RuntimeException("no sql")));
    }

    @Test
    void deadlockIsFoundInTheCauseChainAndInChainedSqlExceptions() {
        assertTrue(SqlErrors.isDeadlock(new PersistenceException("wrapped", DEADLOCK)));
        SQLException head = new SQLException("head", "HY000", 1);
        head.setNextException(DEADLOCK);
        assertTrue(SqlErrors.isDeadlock(head));
    }

    @Test
    void duplicateKeyNeedsStateAndCode() {
        assertTrue(SqlErrors.isDuplicateKey(new SQLIntegrityConstraintViolationException("dup", "23000", 1062)));
        assertTrue(SqlErrors.isDuplicateKey(new PersistenceException("wrapped", new SQLIntegrityConstraintViolationException("dup", "23000", 1062))));
        assertFalse(SqlErrors.isDuplicateKey(new SQLIntegrityConstraintViolationException("check", "23000", 4025)));
        assertFalse(SqlErrors.isDuplicateKey(new SQLException("x", "23000", 1451)), "foreign key violation is not a duplicate");
    }

    @Test
    void unavailableMeansConnectionClass() {
        assertTrue(SqlErrors.isUnavailable(new SQLTransientConnectionException("pool timeout")));
        assertTrue(SqlErrors.isUnavailable(new SQLException("down", "08S01", 0)));
        assertTrue(SqlErrors.isUnavailable(new PersistenceException("wrapped", new SQLTransientConnectionException("x"))));
        assertFalse(SqlErrors.isUnavailable(DEADLOCK));
    }

    @Test
    void transientCoversRetryableErrorsAndNotConstraintViolations() {
        assertTrue(SqlErrors.isTransient(DEADLOCK));
        assertTrue(SqlErrors.isTransient(LOCK_WAIT));
        assertTrue(SqlErrors.isTransient(new SQLTimeoutException("slow")));
        assertTrue(SqlErrors.isTransient(new SQLRecoverableException("reconnect")));
        assertTrue(SqlErrors.isTransient(new SQLException("down", "08001", 0)));
        assertTrue(SqlErrors.isTransient(new PersistenceException("wrapped", new SQLTransientConnectionException("x"))));
        assertFalse(SqlErrors.isTransient(new SQLIntegrityConstraintViolationException("dup", "23000", 1062)));
        assertFalse(SqlErrors.isTransient(new SQLNonTransientException("syntax", "42000", 1064)));
        assertFalse(SqlErrors.isTransient(new IllegalStateException("bug")));
    }
}
