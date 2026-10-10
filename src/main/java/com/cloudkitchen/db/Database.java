package com.cloudkitchen.db;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.LeaseStampStaleException;
import com.cloudkitchen.exception.PersistenceException;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only way application code gets a connection (FR-CON-06, TXR-1..TXR-6). A connection is borrowed for one call,
 * never stored, and always returned with auto-commit restored.
 *
 * <p>{@link #inTransaction} runs the work in one transaction. A deadlock victim (see {@link SqlErrors#isDeadlock})
 * is rolled back and the whole work is run again, up to {@code maxRetries} more times, with a growing pause; after
 * that a {@link PersistenceException} is thrown. Nothing else is retried: validation, constraint and application
 * failures roll back and propagate at once.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final DataSource dataSource;
    private final Clock clock;
    private final int defaultRetries;
    private final long backoffMillis;
    private final LeaseStampHook stampHook;

    public Database(DataSource dataSource, Clock clock, int retryMax, long backoffMillis, LeaseStampHook stampHook) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retryMax < 0 || backoffMillis < 0) {
            throw new IllegalArgumentException("retryMax and backoffMillis must not be negative");
        }
        this.defaultRetries = retryMax;
        this.backoffMillis = backoffMillis;
        this.stampHook = Objects.requireNonNull(stampHook, "stampHook");
    }

    public Database(DataSource dataSource, Clock clock, int retryMax, long backoffMillis) {
        this(dataSource, clock, retryMax, backoffMillis, LeaseStampHook.NO_OP);
    }

    /** Runs {@code work} in one transaction with the configured number of deadlock retries. */
    public <T> T inTransaction(TxWork<T> work) throws CloudKitchenException {
        return inTransaction(work, defaultRetries);
    }

    /** Overload with an explicit number of deadlock retries (0 = run once). */
    public <T> T inTransaction(TxWork<T> work, int maxRetries) throws CloudKitchenException {
        Objects.requireNonNull(work, "work");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return runOnce(work);
            } catch (SQLException e) {
                if (e instanceof StaleStampSqlException) {
                    throw new LeaseStampStaleException(e.getMessage());
                }
                if (SqlErrors.isDeadlock(e) && attempt < maxRetries) {
                    LOG.debug("Deadlock victim, retry {} of {}", attempt + 1, maxRetries);
                    pause(backoffMillis * (attempt + 1));
                    continue;
                }
                throw new PersistenceException(SqlErrors.isDeadlock(e)
                        ? "Deadlock persisted after " + maxRetries + " retries"
                        : "Database error (SQLState " + e.getSQLState() + ", code " + e.getErrorCode() + ")", e);
            }
        }
    }

    /** Runs read-style work on an auto-commit connection (fresh reads, no snapshot held open). */
    public <T> T withConnection(TxWork<T> work) throws CloudKitchenException {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new PersistenceException("Database error (SQLState " + e.getSQLState() + ", code " + e.getErrorCode() + ")", e);
        }
    }

    /** Connections currently lent out by the pool, or -1 if the data source is not a Hikari pool. */
    public int activeConnections() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            return hikari.getHikariPoolMXBean().getActiveConnections();
        }
        return -1;
    }

    @Override
    public void close() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    private <T> T runOnce(TxWork<T> work) throws SQLException, CloudKitchenException {
        try (Connection connection = dataSource.getConnection()) {
            LeaseStampContext context = LeaseStampContext.bind(clock, stampHook);
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                context.beforeCommit();
                connection.commit();
                return result;
            } catch (SQLException | CloudKitchenException | RuntimeException | Error e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                LeaseStampContext.unbind();
                restoreAutoCommit(connection);
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            LOG.warn("Could not restore auto-commit: {}", e.toString());
        }
    }

    private static void pause(long millis) throws PersistenceException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PersistenceException("Interrupted while waiting to retry a deadlocked transaction", e);
        }
    }
}
