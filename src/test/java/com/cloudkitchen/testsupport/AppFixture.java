package com.cloudkitchen.testsupport;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.exception.CloudKitchenException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds whole {@link AppContext} instances on the shared embedded test database: the real wiring (workers, monitor,
 * recovery, sweeper, simulator, shutdown coordinator) with short simulated delays. The first accounts are seeded from
 * random one-time test credentials, never from a file.
 */
public final class AppFixture {

    /** Prefix of the scratch directories this class creates; the cleanup below accepts nothing else. */
    static final String SCRATCH_PREFIX = "app-files-";

    /** Exactly the directories {@link #scratchDirectory()} created in this JVM (Phase 8, P8-D6: they were never deleted). */
    private static final Set<Path> CREATED = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(AppFixture::deleteScratchDirectories, "app-fixture-cleanup"));
    }

    private AppFixture() {
    }

    /**
     * Deletes the scratch directories created by this JVM and nothing else: a path is deleted only if it is in the registry
     * of directories this class created, sits directly under {@code data/test} and starts with {@value #SCRATCH_PREFIX}.
     * Directories of earlier runs, other tests and the user are never listed, matched by pattern or touched.
     *
     * @return how many directories were deleted
     */
    public static int deleteScratchDirectories() {
        Path parent = Path.of("data", "test").toAbsolutePath();
        int deleted = 0;
        for (Path directory : Set.copyOf(CREATED)) {
            boolean ours = directory.getParent() != null && directory.getParent().equals(parent)
                    && directory.getFileName().toString().startsWith(SCRATCH_PREFIX);
            if (ours && Files.isDirectory(directory)) {
                TestDatabase.deleteTree(directory);
                deleted++;
            }
            CREATED.remove(directory);
        }
        return deleted;
    }

    /** The properties of a fast test configuration; {@code overrides} are alternating keys and values. */
    public static Properties properties(DbFixture db, String... overrides) {
        Properties p = db.properties();
        p.setProperty("worker.prep.ms", "0");
        p.setProperty("worker.pack.ms", "0");
        p.setProperty("worker.delivery.ms", "0");
        p.setProperty("shutdown.drain.seconds", "5");
        p.setProperty("shutdown.await.seconds", "5");
        p.setProperty("simulator.max.rate.per.minute", "6000");
        // the order log, the snapshots and the reports of this application belong to this start, never to ./logs or ./snapshots,
        // so that no test can restore another test's snapshot into the shared database
        Path files = scratchDirectory();
        p.setProperty("log.orders.path", files.resolve("logs").resolve("orders.log").toString());
        p.setProperty("snapshot.dir", files.resolve("snapshots").toString());
        p.setProperty("report.export.dir", files.resolve("reports").toString());
        p.setProperty("bootstrap.admin.username", "admin");
        p.setProperty("bootstrap.admin.password", TestDatabase.randomHex(16));
        p.setProperty("bootstrap.staff.username", "staff");
        p.setProperty("bootstrap.staff.password", TestDatabase.randomHex(16));
        for (int i = 0; i < overrides.length; i += 2) {
            p.setProperty(overrides[i], overrides[i + 1]);
        }
        return p;
    }

    /** A new empty directory under {@code data/test} (git-ignored) for the files of one application start. */
    public static Path scratchDirectory() {
        try {
            Path parent = Path.of("data", "test").toAbsolutePath();
            Files.createDirectories(parent);
            Path directory = Files.createTempDirectory(parent, SCRATCH_PREFIX);
            CREATED.add(directory);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A running application context on the test database; the caller closes it. */
    public static AppContext start(DbFixture db, boolean jvmHook, String... overrides) throws CloudKitchenException {
        AppConfig config = AppConfig.fromProperties(properties(db, overrides));
        return AppContext.create(config, db.jdbcUrl(), Clock.systemUTC(), null, jvmHook);
    }
}
