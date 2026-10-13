package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.testsupport.TestDatabase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 defect P8-D2: after the application's JVM was killed, its database server child kept running and locked the data
 * directory, so the next start failed. Ownership is an operating-system lock that dies with its process; a previous owner
 * that ended without a clean stop is recognised by the pid it left in the file.
 */
class DatabaseOwnershipTest {

    private static Path testRoot() throws Exception {
        Path parent = Path.of("data", "test").toAbsolutePath();
        Files.createDirectories(parent);
        return parent;
    }

    private static Path lockFileOf(DbBootstrap.Settings settings) {
        return settings.dataDir().getParent().resolve(DatabaseOwnership.FILE);
    }

    private static String scalar(DbBootstrap server, DbBootstrap.Settings s) throws Exception {
        String url = "jdbc:mysql://" + server.host() + ":" + server.port() + "/" + server.database() + "?sslMode=DISABLED";
        try (Connection c = DriverManager.getConnection(url, s.appUser(), s.appPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 'alive'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** A real application process holding the database; returns once it printed READY. */
    private static Process holder(DbBootstrap.Settings s) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), DatabaseHolderMain.class.getName(), s.baseDir().toString(), s.dataDir().toString(),
                s.tmpDir().toString(), s.socketPath().toString(), String.valueOf(s.port()), s.database(), s.appUser(), s.appPassword())
                .redirectErrorStream(true).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        String line;
        StringBuilder seen = new StringBuilder();
        while (System.nanoTime() < deadline && (line = out.readLine()) != null) {
            if (line.equals("READY")) {
                Thread drain = new Thread(() -> out.lines().forEach(l -> { }), "holder-output-drain");
                drain.setDaemon(true);
                drain.start();
                return process;
            }
            seen.append(line).append('\n');
        }
        process.destroyForcibly();
        throw new AssertionError("the holder never printed READY: " + seen);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    void aServerLeftBehindByAKilledApplicationIsStoppedByTheNextStart() throws Exception {
        DbBootstrap.Settings settings = TestDatabase.newSettings("orphan");
        Process application = holder(settings);
        try {
            List<ProcessHandle> before = TestDatabase.serverProcessesForPort(settings.port());
            assertEquals(1, before.size(), "the application runs one server");
            long leftBehind = before.get(0).pid();
            assertEquals(String.valueOf(application.pid()), Files.readString(lockFileOf(settings), StandardCharsets.US_ASCII).trim(),
                    "the owner file names the application");

            application.destroyForcibly(); // SIGKILL: no hook runs, the server child is not stopped
            application.waitFor(30, TimeUnit.SECONDS);
            assertTrue(alive(leftBehind), "the server outlived its application (the defect)");

            try (DbBootstrap next = DbBootstrap.start(settings)) {
                assertFalse(alive(leftBehind), "the leftover server was stopped");
                List<ProcessHandle> after = TestDatabase.serverProcessesForPort(settings.port());
                assertEquals(1, after.size(), "exactly one server runs now");
                assertTrue(after.get(0).pid() != leftBehind);
                assertEquals("alive", scalar(next, settings));
                assertEquals(String.valueOf(ProcessHandle.current().pid()), Files.readString(lockFileOf(settings), StandardCharsets.US_ASCII).trim());
            }
            assertEquals("", Files.readString(lockFileOf(settings), StandardCharsets.US_ASCII).trim(), "a clean stop empties the owner file");
            assertTrue(TestDatabase.serverProcessesForPort(settings.port()).isEmpty(), "no server remains");
        } finally {
            application.destroyForcibly();
            TestDatabase.deleteTree(settings.dataDir().getParent());
        }
    }

    @Test
    void aLiveApplicationIsNeverTouchedAndTheSecondStartIsRefused() throws Exception {
        DbBootstrap.Settings settings = TestDatabase.newSettings("owned");
        Process application = holder(settings);
        try {
            long server = TestDatabase.serverProcessesForPort(settings.port()).get(0).pid();
            CloudKitchenException refused = assertThrows(CloudKitchenException.class, () -> DbBootstrap.start(settings));
            assertEquals("DB_IN_USE", refused.getCode());
            assertTrue(refused.getMessage().contains(String.valueOf(application.pid())), refused.getMessage());
            assertTrue(alive(server), "the running server was not touched");
            assertTrue(application.isAlive());
            assertEquals(String.valueOf(application.pid()), Files.readString(lockFileOf(settings), StandardCharsets.US_ASCII).trim(),
                    "the refused start left the owner file alone");

            application.destroy(); // SIGTERM: the application's hook stops its server and gives the ownership up
            assertTrue(application.waitFor(60, TimeUnit.SECONDS));
            assertTrue(TestDatabase.serverProcessesForPort(settings.port()).isEmpty(), "a clean stop leaves no server");
            assertEquals("", Files.readString(lockFileOf(settings), StandardCharsets.US_ASCII).trim());
            try (DbBootstrap next = DbBootstrap.start(settings)) {
                assertEquals("alive", scalar(next, settings));
            }
        } finally {
            application.destroyForcibly();
            TestDatabase.deleteTree(settings.dataDir().getParent());
        }
    }

    @Test
    void aSecondStartInTheSameJvmIsRefusedToo() throws Exception {
        DbBootstrap.Settings settings = TestDatabase.newSettings("twice");
        try (DbBootstrap running = DbBootstrap.start(settings)) {
            long server = TestDatabase.serverProcessesForPort(settings.port()).get(0).pid();
            CloudKitchenException refused = assertThrows(CloudKitchenException.class, () -> DbBootstrap.start(settings));
            assertEquals("DB_IN_USE", refused.getCode());
            assertTrue(alive(server));
            assertEquals("alive", scalar(running, settings));
        } finally {
            TestDatabase.deleteTree(settings.dataDir().getParent());
        }
    }

    /**
     * A harmless process whose command line looks like a database server of the given directory: a shell script called
     * {@code mariadbd} (Java reports the executable and its arguments, not argv[0]). Linux/POSIX shell only.
     */
    private static Process fakeServer(Path scriptDir, Path dataDir) throws Exception {
        Path script = Files.createDirectories(scriptDir).resolve("mariadbd");
        if (!Files.exists(script)) {
            Files.writeString(script, "#!/bin/sh\nsleep 300 &\ntrap 'kill $!; exit 0' TERM\nwait $!\n");
            script.toFile().setExecutable(true);
        }
        Process process = new ProcessBuilder(script.toString(), "--datadir=" + dataDir, "--port=1").start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && DatabaseOwnership.serversOf(dataDir).isEmpty()) {
            Thread.sleep(50);
        }
        return process;
    }

    @Test
    void onlyAServerOfAPreviousOwnerOfThisExactDirectoryIsStopped() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"), "needs a POSIX shell");
        Path root = Files.createTempDirectory(testRoot(), "ownership-unit-");
        Path dataDir = root.resolve("db");
        Path other = root.resolve("db2");
        Process wanted = fakeServer(root.resolve("bin"), dataDir);
        Process samePrefix = fakeServer(root.resolve("bin"), other);
        try {
            assertEquals(1, DatabaseOwnership.serversOf(dataDir).size(), "the fake is found by its exact directory");
            // 1. a new directory / an empty owner file: nothing was ever owned here, so a server is not ours to stop
            DatabaseOwnership first = new DatabaseOwnership(dataDir, Duration.ofSeconds(10));
            assertEquals(0, first.acquire());
            assertTrue(wanted.isAlive(), "a server without a previous owner is left alone");
            first.release();
            assertEquals("", Files.readString(root.resolve(DatabaseOwnership.FILE)).trim());
            // 2. a previous owner left its pid: its server is stopped, the one of a directory with the same prefix is not
            Files.writeString(root.resolve(DatabaseOwnership.FILE), "424242\n");
            DatabaseOwnership second = new DatabaseOwnership(dataDir, Duration.ofSeconds(10));
            assertEquals(1, second.acquire());
            assertTrue(wanted.waitFor(10, TimeUnit.SECONDS), "the leftover server of this directory was stopped");
            assertTrue(samePrefix.isAlive(), "a server of the directory db2 was not touched");
            second.release();
        } finally {
            for (Process fake : List.of(wanted, samePrefix)) {
                fake.descendants().forEach(ProcessHandle::destroyForcibly); // the fake's own sleep
                fake.destroyForcibly();
            }
            TestDatabase.deleteTree(root);
        }
    }

    @Test
    void onlyServersOfExactlyThisDataDirectoryAreRecognised() {
        String dir = "--datadir=/data/db";
        assertTrue(DatabaseOwnership.isServerOf("/x/bin/mariadbd --no-defaults --datadir=/data/db --port=1", dir));
        assertTrue(DatabaseOwnership.isServerOf("/x/bin/mariadbd --no-defaults --datadir=/data/db", dir), "at the end of the line");
        assertFalse(DatabaseOwnership.isServerOf("/x/bin/mariadbd --datadir=/data/db2 --port=1", dir), "a longer path is another directory");
        assertFalse(DatabaseOwnership.isServerOf("/x/bin/mariadbd --datadir=/other/data/db --port=1", dir));
        assertFalse(DatabaseOwnership.isServerOf("/usr/bin/vim --datadir=/data/db", dir), "not a database server");
    }
}
