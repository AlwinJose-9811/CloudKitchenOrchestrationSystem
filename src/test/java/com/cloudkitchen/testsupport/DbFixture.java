package com.cloudkitchen.testsupport;

import com.cloudkitchen.bootstrap.DbBootstrap;
import com.cloudkitchen.bootstrap.SchemaInitializer;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.db.DataSourceFactory;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.exception.CloudKitchenException;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One real embedded MariaDB per test JVM (started on first use, stopped by a shutdown hook) and, for each fixture, a
 * freshly built schema: all tables are dropped and V001/V002 are applied again, so test classes never depend on each
 * other's data or on execution order. Surefire runs classes one after the other in one JVM, so the fixtures never
 * overlap. Tests that need to check what the database really holds use {@link #connection()}.
 */
public final class DbFixture implements AutoCloseable {

    private static DbBootstrap server;
    private static DbBootstrap.Settings settings;

    private record SeededItem(long id, java.math.BigDecimal price, boolean active) {
    }

    private final List<SeededItem> seededItems = new ArrayList<>();
    private final AppConfig config;
    private final HikariDataSource pool;
    private final Database database;
    private final Clock clock;

    private final Properties properties;

    private DbFixture(Properties properties, AppConfig config, HikariDataSource pool, Database database, Clock clock) {
        this.properties = properties;
        this.config = config;
        this.pool = pool;
        this.database = database;
        this.clock = clock;
    }

    public static synchronized DbFixture create(Clock clock) throws CloudKitchenException, SQLException {
        startServerOnce();
        Properties props = new Properties();
        props.setProperty("db.user", settings.appUser());
        props.setProperty("db.name", settings.database());
        props.setProperty("db.password", settings.appPassword());
        props.setProperty("snapshot.hmac.secret", TestDatabase.randomHex(32));
        props.setProperty("db.pool.max.size", "20");
        props.setProperty("db.pool.connection.timeout.ms", "5000");
        props.setProperty("tx.retry.backoff.ms", "5");
        AppConfig config = AppConfig.fromProperties(props);
        HikariDataSource pool = DataSourceFactory.create(config, DataSourceFactory.embeddedUrl("127.0.0.1", settings.port(), settings.database()));
        dropAllTables(pool);
        Database database = new Database(pool, clock, config.txRetryMax(), config.txRetryBackoffMs());
        new SchemaInitializer(database, clock).apply();
        DbFixture fixture = new DbFixture(props, config, pool, database, clock);
        fixture.rememberSeed();
        return fixture;
    }

    private static void startServerOnce() throws CloudKitchenException {
        if (server != null) {
            return;
        }
        settings = TestDatabase.newSettings("shared");
        server = DbBootstrap.start(settings);
        DbBootstrap started = server;
        DbBootstrap.Settings used = settings;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            started.close();
            TestDatabase.deleteTree(TestDatabase.rootOf(used));
        }, "test-db-shutdown"));
    }

    private static void dropAllTables(HikariDataSource pool) throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                s.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            s.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private void rememberSeed() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, price, active FROM menu_items ORDER BY id")) {
            while (rs.next()) {
                seededItems.add(new SeededItem(rs.getLong(1), rs.getBigDecimal(2), rs.getBoolean(3)));
            }
        }
    }

    /**
     * Puts the database back to exactly what V002 seeded: deletes orders, history and users, deletes menu items and
     * inventory rows that tests added, restores seeded prices, flags and stations, and resets stock to 50 / 10.
     */
    public void resetData() throws SQLException {
        long maxSeeded = seededItems.stream().mapToLong(SeededItem::id).max().orElse(0);
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM order_status_history");
            s.execute("DELETE FROM order_items");
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM staff_users");
            s.execute("DELETE FROM inventory WHERE item_id > " + maxSeeded);
            s.execute("DELETE FROM menu_items WHERE id > " + maxSeeded);
            s.execute("UPDATE stations SET active = 1");
            s.execute("UPDATE inventory SET stock_qty = 50, reorder_level = 10");
            for (SeededItem item : seededItems) {
                s.execute("UPDATE menu_items SET price = " + item.price().toPlainString() + ", active = " + (item.active() ? 1 : 0)
                        + " WHERE id = " + item.id());
            }
        }
    }

    public Database database() {
        return database;
    }

    public Clock clock() {
        return clock;
    }

    public AppConfig config() {
        return config;
    }

    /** A copy of the properties of this fixture's configuration (database coordinates and secrets of the test server). */
    public Properties properties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    /** The JDBC URL of the shared test server, for building an {@code AppContext} on the same database. */
    public String jdbcUrl() {
        return DataSourceFactory.embeddedUrl("127.0.0.1", settings.port(), settings.database());
    }

    public HikariDataSource pool() {
        return pool;
    }

    public int port() {
        return settings.port();
    }

    /** A plain connection for assertions; the caller closes it. */
    public Connection connection() throws SQLException {
        return pool.getConnection();
    }

    @Override
    public void close() {
        pool.close();
    }
}
