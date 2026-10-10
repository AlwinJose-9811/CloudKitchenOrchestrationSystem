package com.cloudkitchen.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.FakeClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-INV-04. The retry policy is tested with a deterministic seam (a body that throws the server's deadlock error),
 * and the real thing is exercised by two crossing transactions on the embedded server, which InnoDB resolves by
 * choosing one victim with vendor code 1213 (observed, also in Phase 1 spike S-10).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeadlockRetryTest {

    private DbFixture db;
    private Database database;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(new FakeClock());
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

    private static SQLException deadlock() {
        return new SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213);
    }

    @Test
    void aDeadlockIsRetriedAndTheSecondRunSucceeds() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        String result = database.inTransaction(c -> {
            if (runs.incrementAndGet() == 1) {
                throw deadlock();
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, runs.get());
    }

    @Test
    void aPermanentDeadlockFailsAfterExactlyThreeRetries() {
        AtomicInteger runs = new AtomicInteger();
        PersistenceException e = assertThrows(PersistenceException.class, () -> database.inTransaction(c -> {
            runs.incrementAndGet();
            throw deadlock();
        }));
        assertEquals(4, runs.get(), "one run plus exactly three retries");
        assertTrue(e.getMessage().contains("3 retries"));
        assertTrue(SqlErrors.isDeadlock(e));
    }

    @Test
    void success_on_the_last_allowed_retry_counts() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        database.inTransaction(c -> {
            if (runs.incrementAndGet() <= 3) {
                throw deadlock();
            }
            return null;
        });
        assertEquals(4, runs.get());
    }

    @Test
    void theOverloadTakesItsOwnRetryCount() {
        AtomicInteger runs = new AtomicInteger();
        assertThrows(PersistenceException.class, () -> database.inTransaction(c -> {
            runs.incrementAndGet();
            throw deadlock();
        }, 0));
        assertEquals(1, runs.get(), "0 retries: run once");
        runs.set(0);
        assertThrows(PersistenceException.class, () -> database.inTransaction(c -> {
            runs.incrementAndGet();
            throw deadlock();
        }, 1));
        assertEquals(2, runs.get());
    }

    @Test
    void errorsThatAreNotDeadlocksAreNeverRetried() {
        for (SQLException error : List.of(
                new SQLTransactionRollbackException("Lock wait timeout exceeded", "40001", 1205),
                new SQLException("duplicate", "23000", 1062),
                new SQLException("syntax", "42000", 1064))) {
            AtomicInteger runs = new AtomicInteger();
            assertThrows(PersistenceException.class, () -> database.inTransaction(c -> {
                runs.incrementAndGet();
                throw error;
            }));
            assertEquals(1, runs.get(), "code " + error.getErrorCode());
        }
    }

    @Test
    void applicationFailuresAreNeverRetried() {
        AtomicInteger runs = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> database.inTransaction(c -> {
            runs.incrementAndGet();
            throw new IllegalStateException("validation or bug");
        }));
        assertEquals(1, runs.get());
    }

    @Test
    void everyRetryRunsTheWholeBodyAgainAndEarlierWorkWasRolledBack() throws Exception {
        long item = firstItem();
        AtomicInteger runs = new AtomicInteger();
        database.inTransaction(c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE inventory SET stock_qty = stock_qty - 5 WHERE item_id = ?")) {
                p.setLong(1, item);
                p.executeUpdate();
            }
            if (runs.incrementAndGet() < 3) {
                throw deadlock();
            }
            return null;
        });
        assertEquals(45, stockOf(item), "three executions, but only the committed one changed the stock");
    }

    @Test
    void anInterruptDuringTheBackoffStopsRetryingAndKeepsTheFlag() {
        Database slow = new Database(db.pool(), new FakeClock(), 3, 10_000);
        Thread.currentThread().interrupt();
        try {
            assertThrows(PersistenceException.class, () -> slow.inTransaction(c -> {
                throw deadlock();
            }));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted();
        }
    }

    @RepeatedTest(20)
    void twoCrossingTransactionsAreResolvedByOneRetryAndBothCommit() throws Exception {
        db.resetData();
        long a = firstItem();
        long b = a + 1;
        CyclicBarrier bothHoldTheirFirstRow = new CyclicBarrier(2);
        AtomicInteger runsOne = new AtomicInteger();
        AtomicInteger runsTwo = new AtomicInteger();
        CompletableFuture<Void> one = CompletableFuture.runAsync(() -> crossing(a, b, bothHoldTheirFirstRow, runsOne), r -> new Thread(r, "deadlock-one").start());
        CompletableFuture<Void> two = CompletableFuture.runAsync(() -> crossing(b, a, bothHoldTheirFirstRow, runsTwo), r -> new Thread(r, "deadlock-two").start());
        one.get(60, TimeUnit.SECONDS);
        two.get(60, TimeUnit.SECONDS);
        assertTrue(runsOne.get() + runsTwo.get() >= 3, "InnoDB chose a victim, so at least one body ran twice: " + runsOne + "/" + runsTwo);
        assertEquals(48, stockOf(a), "each transaction decremented both rows exactly once");
        assertEquals(48, stockOf(b));
    }

    private void crossing(long first, long second, CyclicBarrier barrier, AtomicInteger runs) {
        try {
            database.inTransaction(c -> {
                boolean firstRun = runs.incrementAndGet() == 1;
                decrement(c, first);
                if (firstRun) {
                    try {
                        barrier.await(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                decrement(c, second);
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void decrement(Connection c, long itemId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE inventory SET stock_qty = stock_qty - 1 WHERE item_id = ?")) {
            p.setLong(1, itemId);
            p.executeUpdate();
        }
    }

    private long firstItem() throws Exception {
        return database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT MIN(item_id) FROM inventory"); ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private int stockOf(long itemId) throws Exception {
        return database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT stock_qty FROM inventory WHERE item_id = ?")) {
                p.setLong(1, itemId);
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }
}
