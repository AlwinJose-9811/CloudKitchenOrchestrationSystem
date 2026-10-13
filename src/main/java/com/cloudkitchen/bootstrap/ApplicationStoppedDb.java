package com.cloudkitchen.bootstrap;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A MariaDB4j {@link DB} that does not register MariaDB4j's own JVM shutdown hook (Phase 8, P8-D1).
 *
 * <p>MariaDB4j registers, unconditionally and without a configuration switch, a hook that stops the database server when the
 * JVM exits. It runs concurrently with the application's own hook, so on Ctrl+C or SIGTERM the server could vanish while the
 * application was still writing its final snapshot and closing its connection pool (the cause of the approved deviation
 * DEV-2 and of the HikariCP warnings). The application now stops the server itself, as the last step of its ordered shutdown
 * ({@link DbBootstrap#close()}); a server that is left behind after a hard kill is handled by {@link DatabaseOwnership}.
 *
 * <p>{@code newEmbeddedDB} of MariaDB4j is reproduced here step by step because the constructor and
 * {@code cleanupOnExit} are only reachable from a subclass.
 */
final class ApplicationStoppedDb extends DB {

    private ApplicationStoppedDb(DBConfiguration configuration) {
        super(configuration);
    }

    static DB create(DBConfiguration configuration) throws ManagedProcessException {
        ApplicationStoppedDb db = new ApplicationStoppedDb(configuration);
        db.prepareDirectories();
        Path dataDir = Path.of(configuration.getDataDir().getAbsolutePath());
        if (Files.isDirectory(dataDir)) {
            try (Stream<Path> content = Files.list(dataDir)) {
                if (content.findFirst().isEmpty()) {
                    db.unpackEmbeddedDb();
                    db.install();
                }
            } catch (java.io.IOException e) {
                throw new ManagedProcessException("Cannot read the data directory " + dataDir, e);
            }
        }
        return db;
    }

    @Override
    protected void cleanupOnExit() {
        // deliberately empty: DbBootstrap.close() stops the server, in order, after the application has finished with it
    }
}
