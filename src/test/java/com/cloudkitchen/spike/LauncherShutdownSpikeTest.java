package com.cloudkitchen.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.TestDatabase;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 spike S-14: the real {@code Launcher.main} runs in a child JVM (embedded database and Tomcat), is stopped
 * with SIGTERM, and its own shutdown hook must stop Tomcat and then the database and say so in its log.
 */
class LauncherShutdownSpikeTest {

    private static final Pattern WEB_PORT = Pattern.compile("Cloud Kitchen is up: http://localhost:(\\d+)/");
    private static final Pattern DB_PORT = Pattern.compile("Embedded database started on 127\\.0\\.0\\.1:(\\d+)");

    @Test
    void sigtermRunsTheShutdownHookWhichStopsTomcatAndTheDatabase() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"),
                "Process.destroy() does not run shutdown hooks on Windows; Windows is unverified");
        Path root = Path.of("data", "test", "launcher-" + TestDatabase.randomHex(4)).toAbsolutePath();
        Files.createDirectories(root);
        Path config = root.resolve("application.properties");
        Files.writeString(config, String.join("\n",
                "db.password=" + TestDatabase.randomHex(16),
                "server.port=0",
                "db.embedded.port=0",
                "db.embedded.data.dir=" + root.resolve("db"),
                "db.embedded.base.dir=" + Path.of("data", "runtime").toAbsolutePath(),
                ""), StandardCharsets.UTF_8);

        // The output goes to a file, not a pipe: Java closes a child's pipe when it exits, and a reader blocked at that
        // moment loses the last lines, which are exactly the shutdown lines this test must see.
        Path logFile = root.resolve("launcher.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dck.config=" + config, "-cp", System.getProperty("java.class.path"),
                "com.cloudkitchen.bootstrap.Launcher").redirectErrorStream(true)
                .redirectOutput(logFile.toFile()).start();
        try {
            int webPort = awaitPort(logFile, WEB_PORT, 120);
            int dbPort = awaitPort(logFile, DB_PORT, 5);
            assertEquals("OK", new HttpTestClient(webPort).get("/health").body());
            assertEquals(1, TestDatabase.serverProcessesForPort(dbPort).size(), "the database must be running");

            child.destroy(); // SIGTERM: the JVM runs its shutdown hooks
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the launcher must exit after SIGTERM");

            List<String> output = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            assertEquals(143, child.exitValue(), "128 + SIGTERM");
            List<String> hookLines = output.stream().filter(l -> l.contains("[ck-shutdown-hook]")).toList();
            assertTrue(hookLines.stream().anyMatch(l -> l.contains("Embedded database stopped")), "hook lines " + hookLines + ", all output: " + output);
            assertTrue(hookLines.stream().anyMatch(l -> l.contains("Cloud Kitchen stopped")), "hook lines " + hookLines + ", all output: " + output);
            assertTrue(TestDatabase.serverProcessesForPort(dbPort).isEmpty(), "no database process may remain");
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", webPort).close(), "the web port must be closed");
            assertFalse(output.stream().anyMatch(l -> l.contains(" ERROR ")), "no ERROR line expected: " + output);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
            TestDatabase.deleteTree(root);
        }
    }

    private static int awaitPort(Path logFile, Pattern pattern, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (Files.exists(logFile)) {
                for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group(1));
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("pattern " + pattern + " not seen within " + seconds + " s in " + logFile);
    }
}
