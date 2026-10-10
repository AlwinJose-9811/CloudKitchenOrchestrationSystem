package com.cloudkitchen.config;

import com.cloudkitchen.exception.ConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Typed view of {@code config/application.properties} (SDD section 15): one getter per documented key, with the
 * documented default, range checks, and the cross-key rules (lease timing, sweeper timing, simulator rates). A value
 * that is out of range fails at load time with a {@link ConfigurationException} naming the key, never with a
 * confusing error later. Keys that are not documented are ignored.
 *
 * <p>Values of secret keys are never printed: {@link #toString()} masks them. A secret that still carries the
 * placeholder from the committed example file is rejected, so the application refuses to start with it.
 */
public final class AppConfig {

    /** Prefix of every placeholder value in {@code application.properties.example}. */
    public static final String PLACEHOLDER_PREFIX = "CHANGE_ME";
    /** System property that overrides the configuration file location. */
    public static final String CONFIG_PROPERTY = "ck.config";
    public static final Path DEFAULT_PATH = Path.of("config", "application.properties");

    public static final int MIN_HMAC_SECRET_LENGTH = 32;
    public static final int MIN_PBKDF2_ITERATIONS = 600_000;
    /** The locked dashboard refresh period; the key only lets the JSP read one number. */
    public static final int DASHBOARD_REFRESH_SECONDS = 5;

    private static final Pattern DB_PASSWORD_SHAPE = Pattern.compile("[A-Za-z0-9_.!@#%^*+=-]{12,128}");
    private static final Set<String> SECRET_KEYS = Set.of("db.password", "snapshot.hmac.secret",
            "bootstrap.admin.password", "bootstrap.staff.password");

    private final Properties values;

    private AppConfig(Properties values) {
        this.values = values;
    }

    /** Loads the file named by {@code -Dck.config}, or {@code config/application.properties}. */
    public static AppConfig load() {
        String override = System.getProperty(CONFIG_PROPERTY);
        return load(override == null || override.isBlank() ? DEFAULT_PATH : Path.of(override));
    }

    public static AppConfig load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration file not found: " + path
                    + ". Copy config/application.properties.example to config/application.properties and edit it.");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read configuration file " + path, e);
        }
        return fromProperties(properties);
    }

    /** Used by tests, which generate their own values and never read or write the real file. */
    public static AppConfig fromProperties(Properties source) {
        Properties copy = new Properties();
        for (String key : source.stringPropertyNames()) {
            copy.setProperty(key, source.getProperty(key).trim());
        }
        AppConfig config = new AppConfig(copy);
        config.validate();
        return config;
    }

    private void validate() {
        for (String key : new TreeSet<>(SECRET_KEYS)) {
            String value = values.getProperty(key);
            if (value != null && value.startsWith(PLACEHOLDER_PREFIX)) {
                throw new ConfigurationException("Configuration key '" + key
                        + "' still has the placeholder value from the example file; set a real value in the git-ignored file");
            }
        }
        String password = values.getProperty("db.password");
        if (password == null || password.isEmpty()) {
            throw new ConfigurationException("Required configuration key 'db.password' is missing");
        }
        if (!DB_PASSWORD_SHAPE.matcher(password).matches()) {
            throw new ConfigurationException("db.password must be 12-128 characters from A-Z a-z 0-9 _ . ! @ # % ^ * + = -");
        }
        String hmac = values.getProperty("snapshot.hmac.secret");
        if (hmac == null || hmac.isEmpty()) {
            throw new ConfigurationException("Required configuration key 'snapshot.hmac.secret' is missing");
        }
        if (hmac.length() < MIN_HMAC_SECRET_LENGTH) {
            throw new ConfigurationException("snapshot.hmac.secret must be at least " + MIN_HMAC_SECRET_LENGTH + " characters");
        }
        String mode = dbMode();
        if (!mode.equals("embedded") && !mode.equals("external")) {
            throw new ConfigurationException("db.mode must be 'embedded' or 'external', was '" + mode + "'");
        }
        if (mode.equals("external") && dbUrl().isEmpty()) {
            throw new ConfigurationException("db.url is required when db.mode is 'external'");
        }
        // Reading every getter once checks its range and type, so a bad value fails here and not at first use.
        serverPort();
        sessionCookieSecure();
        dbEmbeddedPort();
        dbPoolMaxSize();
        dbPoolConnectionTimeoutMs();
        txRetryMax();
        txRetryBackoffMs();
        stationQueueCapacity();
        workerPrepMs();
        workerPackMs();
        workerDeliveryMs();
        workerRetryMax();
        workerRetryBackoffMs();
        workerReacquireMax();
        workerReconcileBatch();
        workerLeaseStampRetry();
        workerRestartMax();
        workerRestartWindowSeconds();
        workerHealthIntervalSeconds();
        compensationBackoffSeconds();
        compensationAlertAttempts();
        compensationRetryMax();
        recoverySweepEnabled();
        recoverySweepInitialDelaySeconds();
        recoverySweepIntervalSeconds();
        recoverySweepBatch();
        simulatorAutostart();
        snapshotIntervalSeconds();
        snapshotRetainCount();
        snapshotMaxBytes();
        shutdownDrainSeconds();
        shutdownAwaitSeconds();
        lockoutMaxAttempts();
        lockoutMinutes();
        pbkdf2Iterations();
        dashboardRefreshSeconds();
        checkCrossKeyRules();
    }

    private void checkCrossKeyRules() {
        int ttl = workerLeaseTtlSeconds();
        int renew = workerLeaseRenewSeconds();
        if (3L * renew > ttl) {
            throw new ConfigurationException("worker.lease.renew.seconds x 3 must not exceed worker.lease.ttl.seconds");
        }
        if (workerStallSeconds() <= ttl) {
            throw new ConfigurationException("worker.stall.seconds must exceed worker.lease.ttl.seconds");
        }
        if (10L * workerLeaseMarginMs() > renew * 1000L) {
            throw new ConfigurationException("10 x worker.lease.margin.ms must not exceed worker.lease.renew.seconds");
        }
        if (compensationBackoffMaxSeconds() < compensationBackoffSeconds()) {
            throw new ConfigurationException("compensation.backoff.max.seconds must not be below compensation.backoff.seconds");
        }
        long offerMs = queueOfferTimeoutMs();
        if (recoveryDispatchWindowSeconds() * 1000L <= offerMs) {
            throw new ConfigurationException("recovery.dispatch.window.seconds must exceed queue.offer.timeout.ms");
        }
        if (recoveryOrphanAfterSeconds() * 1000L < 10 * offerMs) {
            throw new ConfigurationException("recovery.orphan.after.seconds must be at least 10 x queue.offer.timeout.ms");
        }
        if (simulatorDefaultRatePerMinute() > simulatorMaxRatePerMinute()) {
            throw new ConfigurationException("simulator.default.rate.per.minute must not exceed simulator.max.rate.per.minute");
        }
    }

    /** {@code server.port}, default 8080; 0 selects a random port (tests). */
    public int serverPort() {
        return intValue("server.port", 8080, 0, 65535);
    }

    public boolean sessionCookieSecure() {
        return booleanValue("server.session.cookie.secure", false);
    }

    public String dbMode() {
        return values.getProperty("db.mode", "embedded");
    }

    public String dbName() {
        return values.getProperty("db.name", "cloudkitchen");
    }

    public String dbUser() {
        return values.getProperty("db.user", "ckapp");
    }

    /** Secret. Callers must not log it. */
    public String dbPassword() {
        return values.getProperty("db.password");
    }

    public int dbEmbeddedPort() {
        return intValue("db.embedded.port", 3307, 0, 65535);
    }

    public Path dbEmbeddedDataDir() {
        return Path.of(values.getProperty("db.embedded.data.dir", "./data/db"));
    }

    public Path dbEmbeddedBaseDir() {
        return Path.of(values.getProperty("db.embedded.base.dir", "./data/runtime"));
    }

    // ---- database (continued) ----

    /** JDBC URL; required when {@code db.mode=external}, derived by the bootstrap for embedded mode. */
    public Optional<String> dbUrl() {
        String url = values.getProperty("db.url");
        return url == null || url.isEmpty() ? Optional.empty() : Optional.of(url);
    }

    public int dbPoolMaxSize() {
        return intValue("db.pool.max.size", 10, 1, 100);
    }

    public int dbPoolConnectionTimeoutMs() {
        return intValue("db.pool.connection.timeout.ms", 5000, 250, 60_000);
    }

    /** Deadlock retries. */
    public int txRetryMax() {
        return intValue("tx.retry.max", 3, 0, 10);
    }

    public int txRetryBackoffMs() {
        return intValue("tx.retry.backoff.ms", 25, 1, 5000);
    }

    // ---- pipeline ----

    public int stationQueueCapacity() {
        return intValue("station.queue.capacity", 50, 1, 10_000);
    }

    public int queueOfferTimeoutMs() {
        return intValue("queue.offer.timeout.ms", 500, 1, 60_000);
    }

    public int workerPrepMs() {
        return intValue("worker.prep.ms", 3000, 0, 600_000);
    }

    public int workerPackMs() {
        return intValue("worker.pack.ms", 1000, 0, 600_000);
    }

    public int workerDeliveryMs() {
        return intValue("worker.delivery.ms", 4000, 0, 600_000);
    }

    /** Consecutive transient failures before a worker parks an order (RR-8). */
    public int workerRetryMax() {
        return intValue("worker.retry.max", 8, 1, 100);
    }

    public int workerRetryBackoffMs() {
        return intValue("worker.retry.backoff.ms", 200, 1, 5000);
    }

    public int workerLeaseTtlSeconds() {
        return intValue("worker.lease.ttl.seconds", 30, 3, 3600);
    }

    public int workerLeaseRenewSeconds() {
        return intValue("worker.lease.renew.seconds", 10, 1, 1200);
    }

    public int workerHealthIntervalSeconds() {
        return intValue("worker.health.interval.seconds", 10, 1, 600);
    }

    public int workerStallSeconds() {
        return intValue("worker.stall.seconds", 60, 4, 7200);
    }

    public int workerRestartMax() {
        return intValue("worker.restart.max", 5, 0, 100);
    }

    public int workerRestartWindowSeconds() {
        return intValue("worker.restart.window.seconds", 300, 1, 86_400);
    }

    public int workerLeaseMarginMs() {
        return intValue("worker.lease.margin.ms", 200, 50, 2000);
    }

    public int workerLeaseStampRetry() {
        return intValue("worker.lease.stamp.retry", 3, 0, 10);
    }

    public int workerReacquireMax() {
        return intValue("worker.reacquire.max", 3, 0, 10);
    }

    public int workerReconcileBatch() {
        return intValue("worker.reconcile.batch", 200, 1, 10_000);
    }

    public int compensationBackoffSeconds() {
        return intValue("compensation.backoff.seconds", 30, 1, 3600);
    }

    public int compensationBackoffMaxSeconds() {
        return intValue("compensation.backoff.max.seconds", 600, 1, 86_400);
    }

    public int compensationAlertAttempts() {
        return intValue("compensation.alert.attempts", 10, 1, 1000);
    }

    /** Extra attempts of the compensation transaction (RR-10). */
    public int compensationRetryMax() {
        return intValue("compensation.retry.max", 3, 0, 10);
    }

    public int recoveryDispatchWindowSeconds() {
        return intValue("recovery.dispatch.window.seconds", 30, 1, 3600);
    }

    public boolean recoverySweepEnabled() {
        return booleanValue("recovery.sweep.enabled", true);
    }

    public int recoverySweepInitialDelaySeconds() {
        return intValue("recovery.sweep.initial.delay.seconds", 30, 0, 3600);
    }

    public int recoverySweepIntervalSeconds() {
        return intValue("recovery.sweep.interval.seconds", 60, 5, 86_400);
    }

    public int recoveryOrphanAfterSeconds() {
        return intValue("recovery.orphan.after.seconds", 120, 30, 86_400);
    }

    public int recoverySweepBatch() {
        return intValue("recovery.sweep.batch", 100, 1, 10_000);
    }

    public int simulatorDefaultRatePerMinute() {
        return intValue("simulator.default.rate.per.minute", 30, 1, 100_000);
    }

    public int simulatorMaxRatePerMinute() {
        return intValue("simulator.max.rate.per.minute", 600, 1, 100_000);
    }

    public boolean simulatorAutostart() {
        return booleanValue("simulator.autostart", false);
    }

    // ---- files, snapshots, shutdown ----

    public Path snapshotDir() {
        return Path.of(values.getProperty("snapshot.dir", "./snapshots"));
    }

    public int snapshotIntervalSeconds() {
        return intValue("snapshot.interval.seconds", 30, 1, 86_400);
    }

    public int snapshotRetainCount() {
        return intValue("snapshot.retain.count", 10, 1, 1000);
    }

    public long snapshotMaxBytes() {
        return longValue("snapshot.max.bytes", 5_242_880L, 1024L, 1_073_741_824L);
    }

    /** Secret (at least 32 characters). Callers must not log it. */
    public String snapshotHmacSecret() {
        return values.getProperty("snapshot.hmac.secret");
    }

    public int shutdownDrainSeconds() {
        return intValue("shutdown.drain.seconds", 20, 0, 600);
    }

    public int shutdownAwaitSeconds() {
        return intValue("shutdown.await.seconds", 10, 1, 600);
    }

    public Path logOrdersPath() {
        return Path.of(values.getProperty("log.orders.path", "./logs/orders.log"));
    }

    public Path reportExportDir() {
        return Path.of(values.getProperty("report.export.dir", "./reports"));
    }

    // ---- security ----

    public int pbkdf2Iterations() {
        return intValue("security.pbkdf2.iterations", MIN_PBKDF2_ITERATIONS, MIN_PBKDF2_ITERATIONS, 10_000_000);
    }

    public int lockoutMaxAttempts() {
        return intValue("security.lockout.max.attempts", 5, 1, 100);
    }

    public Duration lockoutDuration() {
        return Duration.ofMinutes(lockoutMinutes());
    }

    public int lockoutMinutes() {
        return intValue("security.lockout.minutes", 5, 1, 1440);
    }

    public Optional<String> bootstrapAdminUsername() {
        return optional("bootstrap.admin.username");
    }

    /** Secret, first bootstrap only. Callers must not log it. */
    public Optional<String> bootstrapAdminPassword() {
        return optional("bootstrap.admin.password");
    }

    public Optional<String> bootstrapStaffUsername() {
        return optional("bootstrap.staff.username");
    }

    /** Secret, first bootstrap only. Callers must not log it. */
    public Optional<String> bootstrapStaffPassword() {
        return optional("bootstrap.staff.password");
    }

    /** Always 5: the key exists only so the JSP reads one number; any other value is refused. */
    public int dashboardRefreshSeconds() {
        return intValue("dashboard.refresh.seconds", DASHBOARD_REFRESH_SECONDS, DASHBOARD_REFRESH_SECONDS, DASHBOARD_REFRESH_SECONDS);
    }

    private Optional<String> optional(String key) {
        String value = values.getProperty(key);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private boolean booleanValue(String key, boolean defaultValue) {
        String raw = values.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        if (raw.equals("true") || raw.equals("false")) {
            return Boolean.parseBoolean(raw);
        }
        throw new ConfigurationException(key + " must be 'true' or 'false'");
    }

    private long longValue(String key, long defaultValue, long min, long max) {
        String raw = values.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < min || parsed > max) {
                throw new ConfigurationException(key + " must be between " + min + " and " + max + ", was " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer", e);
        }
    }

    private int intValue(String key, int defaultValue, int min, int max) {
        String raw = values.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < min || parsed > max) {
                throw new ConfigurationException(key + " must be between " + min + " and " + max + ", was " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer", e);
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("AppConfig[");
        boolean first = true;
        for (String key : new TreeSet<>(values.stringPropertyNames())) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(key).append('=').append(SECRET_KEYS.contains(key) ? "[MASKED]" : values.getProperty(key));
        }
        return text.append(']').toString();
    }
}
