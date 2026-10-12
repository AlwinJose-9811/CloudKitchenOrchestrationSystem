package com.cloudkitchen.bootstrap;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.exception.CloudKitchenException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and stops the embedded MariaDB4j server with authentication enforced.
 *
 * <p>MariaDB4j's defaults run {@code mariadbd --skip-grant-tables} on every network interface, so any client
 * without a password gets full access. This class therefore bootstraps once with networking disabled (private
 * socket only): it removes the installer's anonymous accounts and the {@code test} database and creates one
 * application account. Every later start enforces grants and binds to loopback only.
 */
public final class DbBootstrap implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DbBootstrap.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9_.!@#%^*+=-]{12,128}");
    private static final String HARDENED_MARKER = ".ck-hardened";
    private static final String LOOPBACK = "127.0.0.1";

    /** Where the server keeps its files and which application account to create. */
    public record Settings(Path baseDir, Path dataDir, Path tmpDir, Path socketPath, int port,
                           String database, String appUser, String appPassword) {

        public Settings {
            Objects.requireNonNull(baseDir, "baseDir");
            Objects.requireNonNull(dataDir, "dataDir");
            Objects.requireNonNull(tmpDir, "tmpDir");
            Objects.requireNonNull(socketPath, "socketPath");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
            if (!IDENTIFIER.matcher(String.valueOf(database)).matches()
                    || !IDENTIFIER.matcher(String.valueOf(appUser)).matches()) {
                throw new IllegalArgumentException("database and user must match " + IDENTIFIER.pattern());
            }
            // The password is placed in a SQL string literal: quotes and backslashes are rejected outright.
            if (appPassword == null || !PASSWORD.matcher(appPassword).matches()) {
                throw new IllegalArgumentException("password must match " + PASSWORD.pattern());
            }
        }

        @Override
        public String toString() {
            return "Settings[dataDir=" + dataDir + ", port=" + port + ", database=" + database
                    + ", appUser=" + appUser + ", appPassword=[MASKED]]";
        }
    }

    private final Settings settings;
    private final DatabaseOwnership ownership;
    private DB db;

    private DbBootstrap(Settings settings) {
        this.settings = settings;
        this.ownership = new DatabaseOwnership(settings.dataDir(), Duration.ofSeconds(30));
    }

    /** Settings derived from the configuration; the temp and socket directories sit next to the data directory. */
    public static Settings settingsFrom(AppConfig config) throws CloudKitchenException {
        Path dataDir = config.dbEmbeddedDataDir().toAbsolutePath();
        Path root = dataDir.getParent();
        int port = config.dbEmbeddedPort();
        if (port == 0) {
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = probe.getLocalPort();
            } catch (IOException e) {
                throw new CloudKitchenException("DB_BOOTSTRAP_FAILED", "Cannot find a free port for the embedded database", e);
            }
        }
        return new Settings(config.dbEmbeddedBaseDir().toAbsolutePath(), dataDir, root.resolve("tmp"),
                root.resolve("run").resolve("db.sock"), port, config.dbName(), config.dbUser(), config.dbPassword());
    }

    /** Prepares the data directory if needed, starts the server and returns once it accepts connections. */
    public static DbBootstrap start(Settings settings) throws CloudKitchenException {
        DbBootstrap bootstrap = new DbBootstrap(settings);
        try {
            bootstrap.prepareDirectories();
            bootstrap.ownership.acquire();
            if (!Files.exists(settings.dataDir().resolve(HARDENED_MARKER))) {
                bootstrap.bootstrapAccounts();
            }
            bootstrap.db = bootstrap.launch(true);
            LOG.info("Embedded database started on {}:{} (grants enforced, loopback only)", LOOPBACK, settings.port());
            return bootstrap;
        } catch (CloudKitchenException | RuntimeException | Error e) {
            bootstrap.close();
            throw e;
        }
    }

    public String host() {
        return LOOPBACK;
    }

    public int port() {
        return settings.port();
    }

    public String database() {
        return settings.database();
    }

    /** Stops the server. Safe to call more than once; never throws. */
    @Override
    public void close() {
        DB running = db;
        db = null;
        if (running == null) {
            ownership.release(); // a start that failed after the lock was taken: give it up (no server runs)
            return;
        }
        try {
            running.stop();
            ownership.release(); // only after the server is stopped; a failed stop keeps the pid for the next start
            LOG.info("Embedded database stopped");
        } catch (ManagedProcessException | RuntimeException e) {
            LOG.error("Embedded database did not stop cleanly: {}", e.toString());
        }
    }

    private void prepareDirectories() throws CloudKitchenException {
        try {
            Files.createDirectories(settings.dataDir().toAbsolutePath().getParent());
            Files.createDirectories(settings.tmpDir());
            Path socketDir = settings.socketPath().getParent();
            if (!Files.isDirectory(socketDir)) {
                try {
                    Files.createDirectories(socketDir,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException noPosixPermissions) {
                    Files.createDirectories(socketDir); // e.g. Windows: unverified, relies on the loopback-only bind
                }
            }
        } catch (IOException e) {
            throw new CloudKitchenException("DB_BOOTSTRAP_FAILED", "Cannot create database directories under " + settings.dataDir(), e);
        }
    }

    /** One-time phase: socket-only server without grant checks, used to remove weak defaults and add the account. */
    private void bootstrapAccounts() throws CloudKitchenException {
        DB bootstrapDb = null;
        try {
            LOG.info("First start of this data directory: securing the installer defaults");
            bootstrapDb = launch(false);
            String db = settings.database();
            String user = settings.appUser();
            String pw = settings.appPassword();
            bootstrapDb.run("DELETE FROM mysql.global_priv WHERE User='';"
                    + " DELETE FROM mysql.db WHERE User='';"
                    + " DROP DATABASE IF EXISTS test;"
                    + " FLUSH PRIVILEGES;"
                    + " CREATE DATABASE IF NOT EXISTS `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
                    + " CREATE USER IF NOT EXISTS '" + user + "'@'127.0.0.1' IDENTIFIED BY '" + pw + "';"
                    + " CREATE USER IF NOT EXISTS '" + user + "'@'localhost' IDENTIFIED BY '" + pw + "';"
                    + " GRANT ALL PRIVILEGES ON `" + db + "`.* TO '" + user + "'@'127.0.0.1';"
                    + " GRANT ALL PRIVILEGES ON `" + db + "`.* TO '" + user + "'@'localhost';"
                    + " FLUSH PRIVILEGES;");
            bootstrapDb.stop();
            bootstrapDb = null;
            Files.writeString(settings.dataDir().resolve(HARDENED_MARKER), "1\n");
        } catch (IOException e) { // includes ManagedProcessException
            // The client's console output can quote the failing statement, which contains the application password,
            // so neither the message nor the cause is passed on.
            throw new CloudKitchenException("DB_BOOTSTRAP_FAILED", "Securing the embedded database failed (" + e.getClass().getSimpleName()
                    + "; details withheld because the bootstrap statements contain the application password)");
        } finally {
            if (bootstrapDb != null) {
                try {
                    bootstrapDb.stop();
                } catch (ManagedProcessException | RuntimeException e) {
                    LOG.error("Bootstrap server did not stop cleanly: {}", e.toString());
                }
            }
        }
    }

    private DB launch(boolean enforceGrants) throws CloudKitchenException {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setBaseDir(settings.baseDir().toFile());
        cfg.setDataDir(settings.dataDir().toFile());
        cfg.setTmpDir(settings.tmpDir().toString());
        cfg.setSocket(settings.socketPath().toString());
        cfg.setPort(settings.port());
        cfg.setSecurityDisabled(!enforceGrants);
        TlsMaterial.ensure(settings.dataDir().getParent().resolve("tls")).ifPresent(files -> {
            cfg.addArg("--ssl-cert=" + files.cert());
            cfg.addArg("--ssl-key=" + files.key());
        });
        if (enforceGrants) {
            cfg.addArg("--bind-address=" + LOOPBACK);
        } else {
            cfg.addArg("--skip-networking");
        }
        try {
            DB server = ApplicationStoppedDb.create(cfg.build());
            server.start();
            return server;
        } catch (ManagedProcessException e) {
            throw new CloudKitchenException("DB_START_FAILED", "Embedded database failed to start on port " + settings.port()
                    + ": " + e.getMessage(), e);
        }
    }
}
