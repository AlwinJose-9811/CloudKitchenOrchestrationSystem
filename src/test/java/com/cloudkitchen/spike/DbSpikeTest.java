package com.cloudkitchen.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.DbBootstrap;
import com.cloudkitchen.testsupport.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Phase 1 spike (S-02, S-03, S-04, S-05, S-17 and the security hardening): real embedded MariaDB4j server,
 * real HikariCP pool, MySQL Connector/J. No mocks and no in-memory database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbSpikeTest {

    private DbBootstrap.Settings settings;
    private DbBootstrap database;
    private HikariDataSource pool;

    @BeforeAll
    void startDatabase() throws Exception {
        settings = TestDatabase.newSettings("dbspike");
        database = DbBootstrap.start(settings);
        pool = new HikariDataSource(poolConfig(settings.appUser(), settings.appPassword()));
    }

    @AfterAll
    void stopDatabase() {
        if (pool != null) {
            pool.close();
        }
        if (database != null) {
            database.close();
        }
        if (settings != null) {
            assertTrue(TestDatabase.serverProcessesForPort(settings.port()).isEmpty(),
                    "a database process is still running after close()");
            TestDatabase.deleteTree(TestDatabase.rootOf(settings));
        }
    }

    private String jdbcUrl() {
        return "jdbc:mysql://" + database.host() + ":" + database.port() + "/" + database.database()
                + "?sslMode=DISABLED&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci";
    }

    private HikariConfig poolConfig(String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:" + settings.port() + "/" + settings.database()
                + "?sslMode=DISABLED&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(10_000);
        config.setPoolName("ck-spike-pool");
        return config;
    }

    @Test
    void selectOneThroughHikari() throws SQLException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void decimalAndUtf8mb4RoundTripIsExact() throws SQLException {
        String hindi = "नमस्ते किचन";
        String emoji = "pizza 🍕";
        List<BigDecimal> amounts = List.of(new BigDecimal("0.01"), new BigDecimal("19.99"), new BigDecimal("99999999.99"));
        try (Connection connection = pool.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE spike_round_trip (id INT PRIMARY KEY AUTO_INCREMENT,"
                        + " amount DECIMAL(10,2) NOT NULL, label VARCHAR(100) CHARACTER SET utf8mb4 NOT NULL)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO spike_round_trip (amount, label) VALUES (?, ?)")) {
                int i = 0;
                for (BigDecimal amount : amounts) {
                    insert.setBigDecimal(1, amount);
                    insert.setString(2, i++ == 0 ? hindi : emoji);
                    insert.executeUpdate();
                }
            }
            List<BigDecimal> readAmounts = new ArrayList<>();
            List<String> readLabels = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT amount, label FROM spike_round_trip ORDER BY id")) {
                while (rs.next()) {
                    readAmounts.add(rs.getBigDecimal(1));
                    readLabels.add(rs.getString(2));
                }
            }
            assertEquals(amounts, readAmounts, "BigDecimal values (including scale) must come back unchanged");
            assertEquals(hindi, readLabels.get(0));
            assertEquals(emoji, readLabels.get(1));
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT OCTET_LENGTH(label), CHAR_LENGTH(label) FROM spike_round_trip WHERE id = 2")) {
                assertTrue(rs.next());
                assertEquals(emoji.getBytes(StandardCharsets.UTF_8).length, rs.getInt(1),
                        "the emoji must be stored as 4 UTF-8 bytes");
                assertEquals(emoji.codePointCount(0, emoji.length()), rs.getInt(2));
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE spike_round_trip");
            }
        }
    }

    @Test
    void serverRunsWithGrantsEnforcedOnLoopbackAndDurableCommits() throws SQLException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT @@skip_grant_tables, @@bind_address,"
                     + " @@innodb_flush_log_at_trx_commit, @@character_set_server, @@version")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "grant tables must be enforced");
            assertEquals("127.0.0.1", rs.getString(2));
            assertEquals(1, rs.getInt(3), "S-17: durable commits");
            assertTrue(rs.getString(5).contains("MariaDB"));
        }
    }

    @Test
    void anonymousWrongPasswordAndRootAreRejected() {
        for (String[] credentials : new String[][] {{"nobody_at_all", ""}, {"ck_app", "wrong-password"}, {"root", ""}}) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(jdbcUrl(), credentials[0], credentials[1]).close(),
                    "login must fail for " + credentials[0]);
            assertFalse(failure.getMessage().isEmpty());
        }
    }

    @Test
    void applicationAccountCannotReadServerPrivilegeTables() throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT COUNT(*) FROM mysql.user"));
        }
    }

    @Test
    void serverIsStoppedEvenWhenTheBodyFails() {
        DbBootstrap.Settings failing = TestDatabase.newSettings("dbspike-fail");
        try {
            assertThrows(IllegalStateException.class, () -> {
                try (DbBootstrap ignored = DbBootstrap.start(failing)) {
                    assertEquals(1, TestDatabase.serverProcessesForPort(failing.port()).size(),
                            "the server should be running inside the block");
                    throw new IllegalStateException("injected failure");
                }
            });
            assertTrue(TestDatabase.serverProcessesForPort(failing.port()).isEmpty(),
                    "close() must stop the server when the body throws");
        } finally {
            TestDatabase.deleteTree(TestDatabase.rootOf(failing));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // S-06 .. S-19: JDBC behaviour the DAOs and the fencing design rely on
    // ---------------------------------------------------------------------------------------------------------

    private Connection plainConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), settings.appUser(), settings.appPassword());
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static String scalar(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void s06DateTime3UtcRoundTripDoesNotDependOnTheJvmTimeZone() throws SQLException {
        TimeZone original = TimeZone.getDefault();
        Instant instant = Instant.parse("2026-10-10T18:30:15.123Z");
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        try (Connection setup = plainConnection()) {
            exec(setup, "CREATE TABLE s06_time (id INT PRIMARY KEY, ts DATETIME(3) NOT NULL)");
        }
        try {
            int id = 0;
            for (String zone : List.of("UTC", "Asia/Kolkata", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Calendar utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                try (Connection connection = plainConnection()) {
                    id++;
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO s06_time VALUES (?, ?)")) {
                        insert.setInt(1, id);
                        insert.setObject(2, utc);
                        insert.executeUpdate();
                    }
                    id++;
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO s06_time VALUES (?, ?)")) {
                        insert.setInt(1, id);
                        insert.setTimestamp(2, Timestamp.from(instant), utcCalendar);
                        insert.executeUpdate();
                    }
                    try (Statement statement = connection.createStatement();
                         ResultSet rs = statement.executeQuery("SELECT id, ts FROM s06_time WHERE id >= " + (id - 1) + " ORDER BY id")) {
                        while (rs.next()) {
                            assertEquals(utc, rs.getObject(2, LocalDateTime.class), "LocalDateTime in JVM zone " + zone);
                            assertEquals(instant, rs.getTimestamp(2, utcCalendar).toInstant(), "Timestamp+UTC calendar in JVM zone " + zone);
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
        try (Connection connection = plainConnection()) {
            assertEquals("6", scalar(connection, "SELECT COUNT(*) FROM s06_time"));
            assertEquals("1", scalar(connection, "SELECT COUNT(DISTINCT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s.%f')) FROM s06_time"),
                    "the stored text must be identical whatever the JVM zone was");
            assertEquals("2026-10-10 18:30:15.123000", scalar(connection, "SELECT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s.%f') FROM s06_time WHERE id = 1"));
            exec(connection, "DROP TABLE s06_time");
        }
    }

    @Test
    void s07ConditionalUpdateRowCountsAndSelectForUpdate() throws SQLException {
        try (Connection connection = plainConnection()) {
            exec(connection, "CREATE TABLE s07_orders (id INT PRIMARY KEY, status VARCHAR(20) NOT NULL, note VARCHAR(20) NOT NULL)");
            exec(connection, "INSERT INTO s07_orders VALUES (1, 'PLACED', 'x')");
            assertEquals(1, update(connection, "UPDATE s07_orders SET status = ? WHERE id = ? AND status = ?", "VALIDATED", 1, "PLACED"));
            assertEquals(0, update(connection, "UPDATE s07_orders SET status = ? WHERE id = ? AND status = ?", "VALIDATED", 1, "PLACED"),
                    "the predicate no longer matches: 0 rows");
            // Driver default: the count is the number of MATCHED rows, even when the new value equals the old one.
            assertEquals(1, update(connection, "UPDATE s07_orders SET note = ? WHERE id = ? AND status = ?", "x", 1, "VALIDATED"),
                    "matched but unchanged: found-rows semantics by default");
            try (Connection affected = DriverManager.getConnection(jdbcUrl() + "&useAffectedRows=true", settings.appUser(), settings.appPassword())) {
                assertEquals(0, update(affected, "UPDATE s07_orders SET note = ? WHERE id = ? AND status = ?", "x", 1, "VALIDATED"),
                        "with useAffectedRows=true a no-op update reports 0");
            }
            connection.setAutoCommit(false);
            assertEquals("VALIDATED", scalar(connection, "SELECT status FROM s07_orders WHERE id = ? FOR UPDATE", 1));
            connection.rollback();
            connection.setAutoCommit(true);
            exec(connection, "DROP TABLE s07_orders");
        }
    }

    @Test
    void s08GeneratedKeysAreReturned() throws SQLException {
        try (Connection connection = plainConnection()) {
            exec(connection, "CREATE TABLE s08_keys (id BIGINT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(10) NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO s08_keys (v) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, "a");
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    assertEquals(1L, keys.getLong(1));
                }
                insert.setString(1, "b");
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    assertEquals(2L, keys.getLong(1));
                }
            }
            exec(connection, "DROP TABLE s08_keys");
        }
    }

    @Test
    void s09DuplicateKeyGivesSqlState23000AndVendorCode1062() throws SQLException {
        try (Connection connection = plainConnection()) {
            exec(connection, "CREATE TABLE s09_unique (source VARCHAR(10) NOT NULL, external_ref VARCHAR(20) NOT NULL, UNIQUE (source, external_ref))");
            exec(connection, "INSERT INTO s09_unique VALUES ('SWIGGY', 'A-1')");
            SQLException failure = assertThrows(SQLException.class, () -> exec(connection, "INSERT INTO s09_unique VALUES ('SWIGGY', 'A-1')"));
            assertEquals("23000", failure.getSQLState());
            assertEquals(1062, failure.getErrorCode());
            assertTrue(failure instanceof java.sql.SQLIntegrityConstraintViolationException, failure.getClass().getName());
            exec(connection, "INSERT INTO s09_unique VALUES ('ZOMATO', 'A-1')");
            exec(connection, "DROP TABLE s09_unique");
        }
    }

    @RepeatedTest(20)
    void s10CrossingTransactionsDeadlockWithSqlState40001AndCode1213() throws Exception {
        try (Connection setup = plainConnection()) {
            exec(setup, "CREATE TABLE s10_rows (id INT PRIMARY KEY, v INT NOT NULL)");
            exec(setup, "INSERT INTO s10_rows VALUES (1, 0), (2, 0)");
        }
        CountDownLatch bothHoldOneLock = new CountDownLatch(2);
        AtomicReference<SQLException> victim = new AtomicReference<>();
        List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable[] sides = new Runnable[2];
        for (int side = 0; side < 2; side++) {
            final int first = side + 1;
            final int second = 2 - side;
            sides[side] = () -> {
                try (Connection connection = plainConnection()) {
                    connection.setAutoCommit(false);
                    update(connection, "UPDATE s10_rows SET v = v + 1 WHERE id = ?", first);
                    bothHoldOneLock.countDown();
                    if (!bothHoldOneLock.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("barrier timed out");
                    }
                    try {
                        update(connection, "UPDATE s10_rows SET v = v + 1 WHERE id = ?", second);
                        connection.commit();
                        outcomes.add("committed");
                    } catch (SQLException e) {
                        victim.compareAndSet(null, e);
                        connection.rollback();
                        outcomes.add("rolled back");
                    }
                } catch (SQLException | InterruptedException e) {
                    outcomes.add("error " + e);
                }
            };
        }
        CompletableFuture<Void> one = CompletableFuture.runAsync(sides[0]);
        CompletableFuture<Void> two = CompletableFuture.runAsync(sides[1]);
        one.get(30, TimeUnit.SECONDS);
        two.get(30, TimeUnit.SECONDS);
        assertEquals(List.of("committed", "rolled back"), outcomes.stream().sorted().toList(), outcomes.toString());
        assertEquals("40001", victim.get().getSQLState());
        assertEquals(1213, victim.get().getErrorCode());
        assertTrue(victim.get() instanceof java.sql.SQLTransactionRollbackException, victim.get().getClass().getName());
        try (Connection cleanup = plainConnection()) {
            exec(cleanup, "DROP TABLE s10_rows");
        }
    }

    @Test
    void s11CheckConstraintRejectsNegativeStock() throws SQLException {
        try (Connection connection = plainConnection()) {
            exec(connection, "CREATE TABLE s11_stock (item_id INT PRIMARY KEY, stock_qty INT NOT NULL, CHECK (stock_qty >= 0))");
            exec(connection, "INSERT INTO s11_stock VALUES (1, 5)");
            SQLException failure = assertThrows(SQLException.class, () -> exec(connection, "INSERT INTO s11_stock VALUES (2, -1)"));
            assertTrue(failure.getSQLState().startsWith("23"), failure.getSQLState());
            SQLException underflow = assertThrows(SQLException.class,
                    () -> update(connection, "UPDATE s11_stock SET stock_qty = stock_qty - ? WHERE item_id = ?", 6, 1));
            assertTrue(underflow.getSQLState().startsWith("23"), underflow.getSQLState());
            assertEquals(1, update(connection, "UPDATE s11_stock SET stock_qty = stock_qty - ? WHERE item_id = ? AND stock_qty >= ?", 5, 1, 5));
            assertEquals(0, update(connection, "UPDATE s11_stock SET stock_qty = stock_qty - ? WHERE item_id = ? AND stock_qty >= ?", 1, 1, 1),
                    "the conditional deduction must report 0 rows when stock is insufficient");
            exec(connection, "DROP TABLE s11_stock");
        }
    }

    @Test
    void s12SchemaFeaturesTheScriptsUseAreAcceptedAndMultiStatementsAreOff() throws SQLException {
        try (Connection connection = plainConnection()) {
            String ddl = "CREATE TABLE IF NOT EXISTS s12_text (id INT PRIMARY KEY, code VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,"
                    + " name VARCHAR(40) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
            exec(connection, ddl);
            exec(connection, ddl); // second run must be a no-op
            exec(connection, "INSERT INTO s12_text VALUES (1, 'Ab', 'Ab')");
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM s12_text WHERE code = 'ab'"), "utf8mb4_bin is case sensitive");
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM s12_text WHERE name = 'ab'"), "the default collation is case insensitive");
            assertEquals("utf8mb4_unicode_ci", scalar(connection, "SELECT @@collation_database"));
            assertEquals("utf8mb4_unicode_ci", scalar(connection,
                    "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 's12_text' AND COLUMN_NAME = 'name'"));
            assertEquals("utf8mb4_bin", scalar(connection,
                    "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 's12_text' AND COLUMN_NAME = 'code'"));
            assertThrows(SQLException.class, () -> exec(connection, "SELECT 1; SELECT 2"), "multi-statements must be off by default");
            // Finding for the schema scripts: naming only the charset gives the charset's own default collation,
            // not the database default, so every table must state COLLATE explicitly (DATABASE_DESIGN section 3).
            exec(connection, "CREATE TABLE s12_charset_only (name VARCHAR(10)) DEFAULT CHARSET=utf8mb4");
            assertEquals("utf8mb4_general_ci", scalar(connection,
                    "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 's12_charset_only'"));
            exec(connection, "DROP TABLE s12_charset_only");
            exec(connection, "DROP TABLE s12_text");
        }
    }

    @Test
    void s17IsolationAndDurabilityAreRecorded() throws SQLException {
        try (Connection connection = plainConnection()) {
            assertEquals("1", scalar(connection, "SELECT @@innodb_flush_log_at_trx_commit"));
            String isolation = scalar(connection, "SELECT @@transaction_isolation");
            assertEquals("REPEATABLE-READ", isolation);
            System.out.println("SPIKE-RECORD S-17 transaction_isolation=" + isolation
                    + " innodb_flush_log_at_trx_commit=1 innodb_lock_wait_timeout="
                    + scalar(connection, "SELECT @@innodb_lock_wait_timeout"));
        }
    }

    @Test
    void s18LeaseAndCompensationStatementsBehaveAsDesigned() throws SQLException {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 12, 0, 0, 500_000_000);
        try (Connection c = plainConnection()) {
            exec(c, "CREATE TABLE s18_orders (id INT PRIMARY KEY, status VARCHAR(20) NOT NULL,"
                    + " claim_epoch INT NOT NULL DEFAULT 0, claimed_by VARCHAR(40) NULL, lease_expires_at DATETIME(3) NULL,"
                    + " compensation_state VARCHAR(10) NULL,"
                    + " CHECK (claim_epoch >= 0), CHECK (compensation_state IS NULL OR compensation_state IN ('PENDING','RESOLVED')))");
            exec(c, "INSERT INTO s18_orders (id, status) VALUES (1, 'ROUTED'), (2, 'ROUTED'), (3, 'ROUTED')");
            String acquire = "UPDATE s18_orders SET claim_epoch = claim_epoch + 1, claimed_by = ?, lease_expires_at = ?"
                    + " WHERE id = ? AND status NOT IN ('DELIVERED','CANCELLED','FAILED') AND compensation_state IS NULL"
                    + " AND (lease_expires_at IS NULL OR lease_expires_at <= ? OR claimed_by = ?)";

            // acquire with read-back in the same transaction
            c.setAutoCommit(false);
            assertEquals(1, update(c, acquire, "KITCHEN#1", now.plusSeconds(30), 1, now, "KITCHEN#1"));
            assertEquals("1", scalar(c, "SELECT claim_epoch FROM s18_orders WHERE id = ?", 1));
            c.commit();
            c.setAutoCommit(true);
            assertEquals(0, update(c, acquire, "KITCHEN#2", now.plusSeconds(30), 1, now, "KITCHEN#2"), "a valid lease of another owner blocks acquire");
            assertEquals(1, update(c, acquire, "KITCHEN#1", now.plusSeconds(30), 1, now, "KITCHEN#1"), "the same owner string may re-acquire");
            assertEquals("2", scalar(c, "SELECT claim_epoch FROM s18_orders WHERE id = ?", 1));
            assertEquals(1, update(c, acquire, "KITCHEN#2", now.plusSeconds(90), 1, now.plusSeconds(31), "KITCHEN#2"),
                    "an expired lease can be taken over by another owner");
            assertEquals("3", scalar(c, "SELECT claim_epoch FROM s18_orders WHERE id = ?", 1));

            // fenced step: epoch + owner + unexpired lease, and a NULL lease never matches
            String fenced = "UPDATE s18_orders SET status = ?, lease_expires_at = ? WHERE id = ? AND status = ?"
                    + " AND claim_epoch = ? AND claimed_by = ? AND lease_expires_at > ?";
            assertEquals(0, update(c, fenced, "PREPARING", now.plusSeconds(100), 1, "ROUTED", 2, "KITCHEN#1", now.plusSeconds(32)), "stale epoch/owner");
            assertEquals(0, update(c, fenced, "PREPARING", now.plusSeconds(100), 1, "ROUTED", 3, "KITCHEN#2", now.plusSeconds(100)), "expired lease");
            assertEquals(0, update(c, fenced, "PREPARING", now.plusSeconds(100), 2, "ROUTED", 0, null, now), "NULL lease never matches");
            assertEquals(1, update(c, fenced, "PREPARING", now.plusSeconds(100), 1, "ROUTED", 3, "KITCHEN#2", now.plusSeconds(32)));
            assertEquals("2026-10-10 12:01:40.500", scalar(c, "SELECT DATE_FORMAT(lease_expires_at, '%Y-%m-%d %H:%i:%s.%f') FROM s18_orders WHERE id = 1").substring(0, 23),
                    "sub-second precision survives");

            // generic transition with the CASE expression that resolves a pending compensation
            exec(c, "UPDATE s18_orders SET compensation_state = 'PENDING' WHERE id IN (2, 3)");
            String transition = "UPDATE s18_orders SET status = ?, compensation_state = CASE WHEN compensation_state = 'PENDING'"
                    + " AND ? IN ('FAILED','CANCELLED') THEN 'RESOLVED' ELSE compensation_state END WHERE id = ? AND status = ?";
            assertEquals(1, update(c, transition, "FAILED", "FAILED", 2, "ROUTED"));
            assertEquals("RESOLVED", scalar(c, "SELECT compensation_state FROM s18_orders WHERE id = 2"));
            assertEquals(1, update(c, transition, "PREPARING", "PREPARING", 3, "ROUTED"));
            assertEquals("PENDING", scalar(c, "SELECT compensation_state FROM s18_orders WHERE id = 3"), "other targets leave the marker alone");
            assertEquals(0, update(c, "UPDATE s18_orders SET status = 'ROUTED' WHERE id = ? AND status = ? AND compensation_state IS NULL", 3, "PREPARING"));

            // SELECT ... FOR UPDATE filtered by claimed_by, and clearing a retired owner
            c.setAutoCommit(false);
            try (PreparedStatement select = c.prepareStatement("SELECT id FROM s18_orders WHERE claimed_by = ? AND status NOT IN ('DELIVERED','CANCELLED','FAILED') FOR UPDATE")) {
                select.setString(1, "KITCHEN#2");
                try (ResultSet rs = select.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertFalse(rs.next());
                }
            }
            assertEquals(1, update(c, "UPDATE s18_orders SET lease_expires_at = NULL, claimed_by = NULL WHERE claimed_by = ? AND status NOT IN ('DELIVERED','CANCELLED','FAILED')", "KITCHEN#2"));
            c.commit();
            c.setAutoCommit(true);

            // CHECK (x IS NULL OR x IN (...))
            assertThrows(SQLException.class, () -> exec(c, "UPDATE s18_orders SET compensation_state = 'BOGUS' WHERE id = 1"));
            exec(c, "UPDATE s18_orders SET compensation_state = NULL WHERE id = 1");
            assertThrows(SQLException.class, () -> exec(c, "UPDATE s18_orders SET claim_epoch = -1 WHERE id = 1"));

            // nullable DATETIME(3) comparisons are unknown for NULL
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM s18_orders WHERE id = 1 AND lease_expires_at > ?", now));
            assertEquals("1", scalar(c, "SELECT COUNT(*) FROM s18_orders WHERE id = 1 AND (lease_expires_at IS NULL OR lease_expires_at <= ?)", now));
            exec(c, "DROP TABLE s18_orders");
        }
    }

    @RepeatedTest(20)
    void s19ConditionalUpdateBlocksThenReEvaluatesItsPredicateOnTheCommittedRow() throws Exception {
        try (Connection setup = plainConnection()) {
            exec(setup, "CREATE TABLE s19_orders (id INT PRIMARY KEY, status VARCHAR(10) NOT NULL)");
            exec(setup, "INSERT INTO s19_orders VALUES (1, 'A')");
        }
        long injectedDelayMillis = 600;
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        try (Connection first = plainConnection(); Connection second = plainConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(true);
            assertEquals(1, update(first, "UPDATE s19_orders SET status = 'B' WHERE id = 1 AND status = 'A'"));
            CompletableFuture<long[]> competing = CompletableFuture.supplyAsync(() -> {
                try {
                    firstHoldsTheLock.await();
                    long start = System.nanoTime();
                    int rows = update(second, "UPDATE s19_orders SET status = 'C' WHERE id = 1 AND status = 'A'");
                    return new long[] {rows, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)};
                } catch (SQLException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            firstHoldsTheLock.countDown();
            Thread.sleep(injectedDelayMillis); // injected delay between the UPDATE and the commit
            first.commit();
            long[] result = competing.get(20, TimeUnit.SECONDS);
            assertEquals(0, result[0], "the competing UPDATE must re-evaluate 'status = A' against the committed row: no lost update");
            assertTrue(result[1] >= injectedDelayMillis / 2, "the competing UPDATE must have waited for the commit, waited ms=" + result[1]);
            assertEquals("B", scalar(second, "SELECT status FROM s19_orders WHERE id = 1"));
            System.out.println("SPIKE-RECORD S-19 competing UPDATE waited " + result[1] + " ms, affected rows " + result[0]);
        }
        try (Connection cleanup = plainConnection()) {
            exec(cleanup, "DROP TABLE s19_orders");
        }
    }

    @Test
    void s19LockWaitTimeoutErrorIsRecorded() throws Exception {
        try (Connection setup = plainConnection()) {
            exec(setup, "CREATE TABLE s19b_orders (id INT PRIMARY KEY, status VARCHAR(10) NOT NULL)");
            exec(setup, "INSERT INTO s19b_orders VALUES (1, 'B')");
        }
        // lock wait timeout: record the setting and the error that appears when it expires
        try (Connection holder = plainConnection(); Connection waiter = plainConnection()) {
            holder.setAutoCommit(false);
            assertEquals(1, update(holder, "UPDATE s19b_orders SET status = 'D' WHERE id = 1 AND status = 'B'"));
            exec(waiter, "SET SESSION innodb_lock_wait_timeout = 1");
            SQLException timeout = assertThrows(SQLException.class, () -> update(waiter, "UPDATE s19b_orders SET status = 'E' WHERE id = 1 AND status = 'B'"));
            assertEquals(1205, timeout.getErrorCode());
            System.out.println("SPIKE-RECORD S-19 lock wait timeout error: code=" + timeout.getErrorCode() + " sqlState=" + timeout.getSQLState()
                    + " class=" + timeout.getClass().getSimpleName() + " default innodb_lock_wait_timeout=" + scalar(holder, "SELECT @@global.innodb_lock_wait_timeout"));
            holder.rollback();
        }
        try (Connection cleanup = plainConnection()) {
            exec(cleanup, "DROP TABLE s19b_orders");
        }
    }
}
