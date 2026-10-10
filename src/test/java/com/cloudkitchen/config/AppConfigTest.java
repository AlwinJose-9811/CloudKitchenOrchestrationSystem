package com.cloudkitchen.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.cloudkitchen.exception.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Defaults, required keys, ranges, cross-key rules, placeholder rejection and secret masking for every key of SDD 15. */
class AppConfigTest {

    private static final String HMAC = "generated-test-hmac-secret-0123456789-abcdef";

    private static Properties valid() {
        Properties p = new Properties();
        p.setProperty("db.password", "generated-test-password-1");
        p.setProperty("snapshot.hmac.secret", HMAC);
        return p;
    }

    @Test
    void defaultsApplyWhenOnlyTheRequiredKeyIsPresent() {
        AppConfig config = AppConfig.fromProperties(valid());
        assertEquals(8080, config.serverPort());
        assertEquals("embedded", config.dbMode());
        assertEquals(3307, config.dbEmbeddedPort());
        assertEquals(Path.of("./data/db"), config.dbEmbeddedDataDir());
        assertFalse(config.sessionCookieSecure());
    }

    @Test
    void portZeroMeansRandomAndIsAccepted() {
        Properties p = valid();
        p.setProperty("server.port", "0");
        assertEquals(0, AppConfig.fromProperties(p).serverPort());
    }

    @Test
    void missingPasswordIsRejected() {
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(new Properties()));
    }

    @Test
    void placeholderSecretsAreRejected() {
        Properties p = valid();
        p.setProperty("db.password", AppConfig.PLACEHOLDER_PREFIX + "_db_password");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(p));
        assertTrue(e.getMessage().contains("db.password"));
        assertFalse(e.getMessage().contains(AppConfig.PLACEHOLDER_PREFIX + "_db_password"));
    }

    @Test
    void invalidNumbersAndModesAreRejected() {
        Properties badPort = valid();
        badPort.setProperty("server.port", "70000");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(badPort));
        Properties notNumber = valid();
        notNumber.setProperty("server.port", "eighty");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(notNumber));
        Properties badMode = valid();
        badMode.setProperty("db.mode", "h2");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(badMode));
    }

    @Test
    void toStringMasksSecrets() {
        String text = AppConfig.fromProperties(valid()).toString();
        assertFalse(text.contains("generated-test-password-1"));
        assertTrue(text.contains("db.password=[MASKED]"));
    }

    @Test
    void missingFileGivesAHelpfulError(@TempDir Path dir) {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> AppConfig.load(dir.resolve("nope.properties")));
        assertTrue(e.getMessage().contains("application.properties.example"));
    }

    @Test
    void loadsAFileAsUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.properties");
        Files.writeString(file, "db.password=generated-test-password-1\nsnapshot.hmac.secret=" + HMAC + "\nserver.port=0\n");
        assertEquals(0, AppConfig.load(file).serverPort());
    }

    @Test
    void theExampleFileIsUsableOnceItsPlaceholdersAreReplaced(@TempDir Path dir) throws IOException {
        String edited = Files.readString(Path.of("config", "application.properties.example"))
                .replaceAll("CHANGE_ME[A-Za-z0-9_]*", "generated-test-secret-value-0123456789");
        Path file = dir.resolve("application.properties");
        Files.writeString(file, edited);
        AppConfig config = AppConfig.load(file);
        assertEquals(8080, config.serverPort(), "inline comments must not leak into values");
        assertEquals("embedded", config.dbMode());
        assertEquals("cloudkitchen", config.dbName());
        assertEquals(3307, config.dbEmbeddedPort());
    }

    @Test
    void theCommittedExampleFileIsRejectedUntilEdited() {
        Path example = Path.of("config", "application.properties.example");
        assertTrue(Files.isRegularFile(example), "the example file must be committed");
        assertThrows(ConfigurationException.class, () -> AppConfig.load(example));
    }

    private static ConfigurationException rejected(String key, String value) {
        Properties p = valid();
        p.setProperty(key, value);
        return assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(p), key + "=" + value);
    }

    @Test
    void everyDocumentedDefaultMatchesSddSection15() {
        AppConfig c = AppConfig.fromProperties(valid());
        assertEquals(10, c.dbPoolMaxSize());
        assertEquals(5000, c.dbPoolConnectionTimeoutMs());
        assertEquals(3, c.txRetryMax());
        assertEquals(25, c.txRetryBackoffMs());
        assertEquals(50, c.stationQueueCapacity());
        assertEquals(500, c.queueOfferTimeoutMs());
        assertEquals(3000, c.workerPrepMs());
        assertEquals(1000, c.workerPackMs());
        assertEquals(4000, c.workerDeliveryMs());
        assertEquals(8, c.workerRetryMax());
        assertEquals(200, c.workerRetryBackoffMs());
        assertEquals(30, c.workerLeaseTtlSeconds());
        assertEquals(10, c.workerLeaseRenewSeconds());
        assertEquals(10, c.workerHealthIntervalSeconds());
        assertEquals(60, c.workerStallSeconds());
        assertEquals(5, c.workerRestartMax());
        assertEquals(300, c.workerRestartWindowSeconds());
        assertEquals(30, c.compensationBackoffSeconds());
        assertEquals(600, c.compensationBackoffMaxSeconds());
        assertEquals(10, c.compensationAlertAttempts());
        assertEquals(200, c.workerLeaseMarginMs());
        assertEquals(3, c.workerLeaseStampRetry());
        assertEquals(3, c.workerReacquireMax());
        assertEquals(200, c.workerReconcileBatch());
        assertEquals(30, c.recoveryDispatchWindowSeconds());
        assertEquals(3, c.compensationRetryMax());
        assertTrue(c.recoverySweepEnabled());
        assertEquals(30, c.recoverySweepInitialDelaySeconds());
        assertEquals(60, c.recoverySweepIntervalSeconds());
        assertEquals(120, c.recoveryOrphanAfterSeconds());
        assertEquals(100, c.recoverySweepBatch());
        assertEquals(30, c.simulatorDefaultRatePerMinute());
        assertEquals(600, c.simulatorMaxRatePerMinute());
        assertFalse(c.simulatorAutostart());
        assertEquals(Path.of("./snapshots"), c.snapshotDir());
        assertEquals(30, c.snapshotIntervalSeconds());
        assertEquals(10, c.snapshotRetainCount());
        assertEquals(5_242_880L, c.snapshotMaxBytes());
        assertEquals(20, c.shutdownDrainSeconds());
        assertEquals(10, c.shutdownAwaitSeconds());
        assertEquals(Path.of("./logs/orders.log"), c.logOrdersPath());
        assertEquals(Path.of("./reports"), c.reportExportDir());
        assertEquals(600_000, c.pbkdf2Iterations());
        assertEquals(5, c.lockoutMaxAttempts());
        assertEquals(Duration.ofMinutes(5), c.lockoutDuration());
        assertEquals(5, c.dashboardRefreshSeconds());
        assertTrue(c.dbUrl().isEmpty());
        assertTrue(c.bootstrapAdminPassword().isEmpty());
    }

    @Test
    void theHmacSecretIsRequiredAndMustBeAtLeast32Characters() {
        Properties missing = valid();
        missing.remove("snapshot.hmac.secret");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(missing));
        assertTrue(rejected("snapshot.hmac.secret", "x".repeat(31)).getMessage().contains("snapshot.hmac.secret"));
        Properties ok = valid();
        ok.setProperty("snapshot.hmac.secret", "x".repeat(32));
        assertEquals(32, AppConfig.fromProperties(ok).snapshotHmacSecret().length());
    }

    @Test
    void pbkdf2IterationsBelow600000AreRefused() {
        rejected("security.pbkdf2.iterations", "599999");
        rejected("security.pbkdf2.iterations", "1000");
        Properties p = valid();
        p.setProperty("security.pbkdf2.iterations", "650000");
        assertEquals(650_000, AppConfig.fromProperties(p).pbkdf2Iterations());
    }

    @Test
    void theDatabasePasswordMustHaveTheDocumentedShape() {
        rejected("db.password", "short");
        rejected("db.password", "has a space in it 123");
        rejected("db.password", "quote\"inside-123456");
        rejected("db.password", "x".repeat(129));
    }

    @ParameterizedTest(name = "{0}={1} is out of range")
    @CsvSource({"db.pool.max.size,0", "db.pool.max.size,101", "station.queue.capacity,0", "queue.offer.timeout.ms,0",
            "tx.retry.max,-1", "tx.retry.max,11", "worker.lease.margin.ms,49", "worker.lease.margin.ms,2001",
            "recovery.sweep.interval.seconds,4", "recovery.orphan.after.seconds,29", "simulator.max.rate.per.minute,0",
            "snapshot.retain.count,0", "snapshot.max.bytes,10", "security.lockout.max.attempts,0",
            "security.lockout.minutes,0", "worker.retry.max,0", "dashboard.refresh.seconds,4", "dashboard.refresh.seconds,6"})
    void numbersOutsideTheirRangeAreRejectedWithTheKeyName(String key, String value) {
        assertTrue(rejected(key, value).getMessage().contains(key));
    }

    @Test
    void booleansAreStrict() {
        rejected("recovery.sweep.enabled", "yes");
        rejected("simulator.autostart", "TRUE");
        rejected("server.session.cookie.secure", "1");
        Properties p = valid();
        p.setProperty("recovery.sweep.enabled", "false");
        assertFalse(AppConfig.fromProperties(p).recoverySweepEnabled());
    }

    @Test
    void leaseTimingRulesAreEnforced() {
        // 3 x renew must not exceed ttl
        Properties tooSlowRenewal = valid();
        tooSlowRenewal.setProperty("worker.lease.renew.seconds", "11");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(tooSlowRenewal));
        // stall must exceed ttl
        Properties stallTooShort = valid();
        stallTooShort.setProperty("worker.stall.seconds", "30");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(stallTooShort));
        // 10 x margin must not exceed renew (ms): 10 x 2000 > 10 x 1000
        Properties marginTooBig = valid();
        marginTooBig.setProperty("worker.lease.margin.ms", "2000");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(marginTooBig));
        Properties boundaryOk = valid();
        boundaryOk.setProperty("worker.lease.renew.seconds", "10");
        boundaryOk.setProperty("worker.lease.margin.ms", "1000");
        assertEquals(1000, AppConfig.fromProperties(boundaryOk).workerLeaseMarginMs());
    }

    @Test
    void sweeperAndWindowRulesAreEnforced() {
        // orphan.after (seconds) must be at least 10 x offer timeout: 10 x 20000 ms = 200 s > 120 s
        Properties orphan = valid();
        orphan.setProperty("queue.offer.timeout.ms", "20000");
        orphan.setProperty("recovery.dispatch.window.seconds", "60");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(orphan));
        // dispatch window must exceed the offer timeout
        Properties window = valid();
        window.setProperty("queue.offer.timeout.ms", "30000");
        window.setProperty("recovery.dispatch.window.seconds", "30");
        window.setProperty("recovery.orphan.after.seconds", "400");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(window));
    }

    @Test
    void simulatorAndCompensationRelationsAreEnforced() {
        Properties rate = valid();
        rate.setProperty("simulator.default.rate.per.minute", "700");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(rate));
        Properties backoff = valid();
        backoff.setProperty("compensation.backoff.max.seconds", "10");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(backoff));
    }

    @Test
    void externalModeNeedsAUrl() {
        Properties external = valid();
        external.setProperty("db.mode", "external");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(external));
        external.setProperty("db.url", "jdbc:mysql://127.0.0.1:3306/cloudkitchen");
        assertEquals("jdbc:mysql://127.0.0.1:3306/cloudkitchen", AppConfig.fromProperties(external).dbUrl().orElseThrow());
    }

    @Test
    void placeholdersAreRejectedForEveryOtherSecretToo() {
        for (String key : new String[] {"snapshot.hmac.secret", "bootstrap.admin.password", "bootstrap.staff.password"}) {
            ConfigurationException e = rejected(key, AppConfig.PLACEHOLDER_PREFIX + "_something");
            assertTrue(e.getMessage().contains(key));
            assertFalse(e.getMessage().contains(AppConfig.PLACEHOLDER_PREFIX + "_something"));
        }
    }

    @Test
    void toStringMasksEverySecretKey() {
        Properties p = valid();
        p.setProperty("bootstrap.admin.password", "generated-admin-secret-value");
        p.setProperty("bootstrap.staff.password", "generated-staff-secret-value");
        String text = AppConfig.fromProperties(p).toString();
        for (String secret : new String[] {"generated-test-password-1", HMAC, "generated-admin-secret-value", "generated-staff-secret-value"}) {
            assertFalse(text.contains(secret), "secret leaked into toString");
        }
        assertTrue(text.contains("snapshot.hmac.secret=[MASKED]"));
        assertTrue(text.contains("bootstrap.admin.password=[MASKED]"));
    }

    @Test
    void bootstrapCredentialsAreOptionalAndReadFromConfigOnly() {
        Properties p = valid();
        p.setProperty("bootstrap.admin.username", "admin");
        p.setProperty("bootstrap.admin.password", "generated-admin-secret-value");
        AppConfig c = AppConfig.fromProperties(p);
        assertEquals("admin", c.bootstrapAdminUsername().orElseThrow());
        assertEquals("generated-admin-secret-value", c.bootstrapAdminPassword().orElseThrow());
        assertTrue(c.bootstrapStaffUsername().isEmpty());
    }
}
