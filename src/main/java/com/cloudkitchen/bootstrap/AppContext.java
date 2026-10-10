package com.cloudkitchen.bootstrap;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dao.jdbc.JdbcOrderDao;
import com.cloudkitchen.dao.jdbc.JdbcStaffUserDao;
import com.cloudkitchen.db.DataSourceFactory;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.OrderQueryService;
import com.cloudkitchen.service.PasswordHasher;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persistence-side wiring of the application (SDD 2.4, Phase 3 part): connection pool, schema and reference data,
 * the first accounts, and the read-only query service. Creating it either yields a fully initialised database or
 * throws; the application never continues with a half-built schema. Pipeline and write services are added by the
 * phases that introduce them.
 */
public final class AppContext implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final HikariDataSource pool;
    private final Database database;
    private final OrderQueryService orderQueries;

    private AppContext(HikariDataSource pool, Database database, OrderQueryService orderQueries) {
        this.pool = pool;
        this.database = database;
        this.orderQueries = orderQueries;
    }

    /**
     * Opens the pool, applies {@code V001}/{@code V002} (each exactly once, recorded in {@code schema_version}),
     * seeds the initial accounts if the user table is empty and builds the services.
     *
     * @throws CloudKitchenException if the database cannot be reached or a script fails
     */
    public static AppContext create(AppConfig config, String jdbcUrl, Clock clock) throws CloudKitchenException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(clock, "clock");
        HikariDataSource pool = DataSourceFactory.create(config, jdbcUrl);
        try {
            Database database = new Database(pool, clock, config.txRetryMax(), config.txRetryBackoffMs());
            var applied = new SchemaInitializer(database, clock).apply();
            LOG.info("Database schema is current (scripts applied by this start: {})", applied);
            int created = new UserSeeder(database, new JdbcStaffUserDao(clock), new PasswordHasher(config.pbkdf2Iterations())).seedIfEmpty(config);
            LOG.info("Initial accounts created by this start: {}", created);
            OrderQueryService queries = new OrderQueryService(database,
                    new JdbcOrderDao(clock, Duration.ofMillis(config.workerLeaseMarginMs())));
            return new AppContext(pool, database, queries);
        } catch (CloudKitchenException | RuntimeException | Error e) {
            pool.close();
            throw e;
        }
    }

    public Database database() {
        return database;
    }

    public OrderQueryService orderQueries() {
        return orderQueries;
    }

    /** Closes the pool; safe to call twice. */
    @Override
    public void close() {
        pool.close();
    }
}
