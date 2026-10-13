package com.cloudkitchen.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.LeaseStampStaleException;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.FakeClock;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseTransactionTest {

    private FakeClock clock;
    private DbFixture db;
    private Database database;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
        database = db.database();
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws SQLException {
        db.resetData();
    }

    private static int stock(Connection c, long itemId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT stock_qty FROM inventory WHERE item_id = ?")) {
            p.setLong(1, itemId);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int stockOf(long itemId) throws Exception {
        Integer value = database.withConnection(c -> stock(c, itemId));
        return value;
    }

    private long firstItem() throws Exception {
        return database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT MIN(item_id) FROM inventory"); ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private static void decrement(Connection c, long itemId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE inventory SET stock_qty = stock_qty - 1 WHERE item_id = ?")) {
            p.setLong(1, itemId);
            p.executeUpdate();
        }
    }

    @Test
    void durabilityAndIsolationSettingsAreNotWeakened() throws Exception {
        String flush = database.withConnection(c -> scalar(c, "SELECT @@innodb_flush_log_at_trx_commit"));
        String isolation = database.withConnection(c -> scalar(c, "SELECT @@transaction_isolation"));
        assertEquals("1", flush, "TXR-7: every commit is flushed to disk");
        assertEquals("REPEATABLE-READ", isolation);
    }

    @Test
    void multiStatementQueriesAreRefusedByTheDriver() {
        assertThrows(PersistenceException.class, () -> database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT 1; DELETE FROM inventory")) {
                p.execute();
            }
            return null;
        }));
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql); ResultSet rs = p.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void aCommittedTransactionIsVisibleToOtherConnections() throws Exception {
        long item = firstItem();
        database.inTransaction(c -> {
            decrement(c, item);
            return null;
        });
        assertEquals(49, stockOf(item));
    }

    @Test
    void everyKindOfFailureRollsEverythingBack() throws Exception {
        long item = firstItem();
        assertThrows(IllegalStateException.class, () -> database.inTransaction(c -> {
            decrement(c, item);
            throw new IllegalStateException("bug");
        }));
        assertThrows(CloudKitchenException.class, () -> database.inTransaction(c -> {
            decrement(c, item);
            throw new CloudKitchenException("X", "domain failure");
        }));
        assertThrows(PersistenceException.class, () -> database.inTransaction(c -> {
            decrement(c, item);
            try (PreparedStatement p = c.prepareStatement("UPDATE inventory SET stock_qty = -1 WHERE item_id = ?")) {
                p.setLong(1, item);
                p.executeUpdate();
            }
            return null;
        }));
        class Crash extends Error {
            private static final long serialVersionUID = 1L;
        }
        assertThrows(Crash.class, () -> database.inTransaction(c -> {
            decrement(c, item);
            throw new Crash();
        }));
        assertEquals(50, stockOf(item), "no partial effect survived any failure");
    }

    @Test
    void theDomainExceptionPassesThroughUnchanged() {
        CloudKitchenException original = new CloudKitchenException("X", "domain failure");
        CloudKitchenException thrown = assertThrows(CloudKitchenException.class, () -> database.inTransaction(c -> {
            throw original;
        }));
        assertEquals(original, thrown);
    }

    @Test
    void sqlFailuresBecomePersistenceExceptionsWithoutLeakingStatements() throws Exception {
        PersistenceException e = assertThrows(PersistenceException.class, () -> database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM no_such_table")) {
                p.executeQuery();
            }
            return null;
        }));
        assertTrue(e.getCause() instanceof SQLException);
        assertFalse(e.getMessage().contains("no_such_table"), "message has state and code only");
    }

    @Test
    void connectionsAreAlwaysReturnedAndAutoCommitIsRestored() throws Exception {
        assertEquals(0, database.activeConnections());
        assertThrows(IllegalStateException.class, () -> database.inTransaction(c -> {
            assertEquals(1, database.activeConnections());
            assertFalse(c.getAutoCommit());
            throw new IllegalStateException();
        }));
        database.withConnection(c -> {
            assertTrue(c.getAutoCommit());
            return null;
        });
        for (int i = 0; i < 50; i++) {
            database.inTransaction(c -> c.isValid(1));
        }
        assertEquals(0, database.activeConnections(), "pool active count returns to 0 (FR-CON-06)");
    }

    @Test
    void separateCallsUseSeparateConnections() throws Exception {
        long first = database.inTransaction(c -> connectionId(c));
        long second = database.withConnection(c -> connectionId(c));
        long inside = database.inTransaction(c -> {
            long outer = connectionId(c);
            long other = database.withConnection(DatabaseTransactionTest::connectionId);
            assertTrue(outer != other, "a nested call must not reuse the transaction's connection");
            return outer;
        });
        assertTrue(first > 0 && second > 0 && inside > 0);
    }

    private static long connectionId(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT CONNECTION_ID()"); ResultSet rs = p.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void anAutoCommitReadSeesAConcurrentCommitButARepeatableReadSnapshotDoesNot() throws Exception {
        long item = firstItem();
        database.inTransaction(c -> {
            assertEquals(50, stock(c, item), "first read opens the snapshot");
            database.inTransaction(other -> {
                decrement(other, item);
                return null;
            });
            assertEquals(50, stock(c, item), "REPEATABLE READ: the snapshot does not move");
            Integer fresh = database.withConnection(other -> stock(other, item));
            assertEquals(49, fresh.intValue(), "an auto-commit read is fresh (RR-4)");
            return null;
        });
    }

    @Test
    void nonDeadlockErrorsAreNotRetried() {
        AtomicInteger runs = new AtomicInteger();
        assertThrows(PersistenceException.class, () -> database.inTransaction(c -> {
            runs.incrementAndGet();
            throw new SQLException("constraint", "23000", 1062);
        }));
        assertEquals(1, runs.get());
    }

    @Test
    void theStampGuardRollsBackWhenTheCommitComesTooLate() throws Exception {
        long item = firstItem();
        Duration margin = Duration.ofMillis(200);
        LeaseStampStaleException e = assertThrows(LeaseStampStaleException.class, () -> database.inTransaction(c -> {
            LeaseStampContext.stamp(clock, margin, LeaseStampHook.NO_OP);
            decrement(c, item);
            clock.advance(Duration.ofMillis(101)); // beyond margin / 2 = 100 ms before the commit
            return null;
        }));
        assertEquals("LEASE_STAMP_STALE", e.getCode());
        assertEquals(50, stockOf(item), "nothing was committed");
    }

    @Test
    void theStampGuardLetsAPromptCommitThrough() throws Exception {
        long item = firstItem();
        database.inTransaction(c -> {
            LeaseStampContext.stamp(clock, Duration.ofMillis(200), LeaseStampHook.NO_OP);
            decrement(c, item);
            clock.advance(Duration.ofMillis(100)); // exactly the budget
            return null;
        });
        assertEquals(49, stockOf(item));
    }

    @Test
    void theEarliestStampDecidesAndAfterExecuteAlsoGuards() throws Exception {
        assertThrows(LeaseStampStaleException.class, () -> database.inTransaction(c -> {
            LeaseStampContext.stamp(clock, Duration.ofMillis(200), LeaseStampHook.NO_OP);
            clock.advance(Duration.ofMillis(60));
            LeaseStampContext.stamp(clock, Duration.ofMillis(200), LeaseStampHook.NO_OP); // a later stamp does not reset the budget
            clock.advance(Duration.ofMillis(60));
            LeaseStampContext.afterExecute(clock, LeaseStampHook.NO_OP);
            return null;
        }));
    }

    @Test
    void aStampOutsideATransactionRegistersNothing() throws Exception {
        LeaseStampContext.Stamp stamp = LeaseStampContext.stamp(clock, Duration.ofMillis(200), LeaseStampHook.NO_OP);
        assertEquals(clock.instant().plusMillis(200), stamp.usableAfter());
        clock.advance(Duration.ofHours(1));
        LeaseStampContext.afterExecute(clock, LeaseStampHook.NO_OP); // no bound context: no guard, no exception
    }

    @Test
    void hooksSeeEveryPhaseInOrder() throws Exception {
        java.util.List<LeaseStampHook.Phase> seen = new java.util.ArrayList<>();
        Database hooked = new Database(db.pool(), clock, 3, 1, seen::add);
        hooked.inTransaction(c -> {
            LeaseStampContext.stamp(clock, Duration.ofMillis(200), seen::add);
            LeaseStampContext.afterExecute(clock, seen::add);
            return null;
        });
        assertEquals(java.util.List.of(LeaseStampHook.Phase.AFTER_STAMP, LeaseStampHook.Phase.AFTER_EXECUTE, LeaseStampHook.Phase.BEFORE_COMMIT), seen);
    }

    @Test
    void anUnreachableDatabaseGivesAMeaningfulPersistenceException() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DataSourceFactoryProbe.deadUrl());
        config.setUsername("nobody");
        config.setPassword("irrelevant-test-value");
        config.setConnectionTimeout(300);
        config.setInitializationFailTimeout(-1);
        config.setMaximumPoolSize(1);
        try (HikariDataSource dead = new HikariDataSource(config)) {
            Database offline = new Database(dead, clock, 3, 1);
            PersistenceException e = assertThrows(PersistenceException.class, () -> offline.withConnection(c -> null));
            assertTrue(SqlErrors.isUnavailable(e), "classified as unavailable: " + e.getCause());
            assertTrue(SqlErrors.isTransient(e), "an outage is transient (RR-8), never a reason to fail an order");
            assertFalse(e.getMessage().contains("irrelevant-test-value"));
            PersistenceException inTx = assertThrows(PersistenceException.class, () -> offline.inTransaction(c -> null));
            assertInstanceOf(SQLException.class, inTx.getCause());
        }
    }
}
