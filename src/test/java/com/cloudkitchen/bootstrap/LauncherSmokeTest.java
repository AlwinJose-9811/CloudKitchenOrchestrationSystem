package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestDatabase;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Roadmap 8.2, TESTING_STRATEGY "LauncherSmokeTest": the real {@code Launcher.main} runs in a child JVM (embedded database, Tomcat
 * and the whole pipeline), serves the sign-in page, lets the administrator sign in, enter an order and see it delivered, and
 * ends on SIGTERM through the hook path with every shutdown step done, the database process gone and the web port closed.
 * The child's configuration, database, order log, snapshots and reports are in a directory of this test.
 */
@Tag("integration")
class LauncherSmokeTest {

    private static final Pattern WEB_PORT = Pattern.compile("Cloud Kitchen is up: http://localhost:(\\d+)/");
    private static final Pattern DB_PORT = Pattern.compile("Embedded database started on 127\\.0\\.0\\.1:(\\d+)");

    @Test
    void theLauncherServesTheApplicationAndShutsDownCleanlyThroughTheHookPath() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"),
                "Process.destroy() does not run shutdown hooks on Windows; Windows is unverified");
        Path root = Path.of("data", "test", "launcher-smoke-" + TestDatabase.randomHex(4)).toAbsolutePath();
        Files.createDirectories(root);
        String adminPassword = "A" + TestDatabase.randomHex(10);
        Path config = root.resolve("application.properties");
        Files.writeString(config, String.join("\n",
                "db.password=" + TestDatabase.randomHex(16),
                "snapshot.hmac.secret=" + TestDatabase.randomHex(32),
                "bootstrap.admin.username=boss",
                "bootstrap.admin.password=" + adminPassword,
                "bootstrap.staff.username=cook",
                "bootstrap.staff.password=S" + TestDatabase.randomHex(10),
                "server.port=0",
                "db.embedded.port=0",
                "db.embedded.data.dir=" + root.resolve("db"),
                "db.embedded.base.dir=" + Path.of("data", "runtime").toAbsolutePath(),
                "log.orders.path=" + root.resolve("logs").resolve("orders.log"),
                "snapshot.dir=" + root.resolve("snapshots"),
                "report.export.dir=" + root.resolve("reports"),
                "worker.prep.ms=50", "worker.pack.ms=50", "worker.delivery.ms=50",
                ""), StandardCharsets.UTF_8);
        Path logFile = root.resolve("launcher.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dck.config=" + config, "-DCK_APP_LOG_DIR=" + root.resolve("applog"), "-cp", System.getProperty("java.class.path"),
                "com.cloudkitchen.bootstrap.Launcher").redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        try {
            int webPort = awaitPort(child, logFile, WEB_PORT, 120);
            int dbPort = awaitPort(child, logFile, DB_PORT, 5);
            assertEquals(1, TestDatabase.serverProcessesForPort(dbPort).size(), "the database is running");

            HttpTestClient visitor = HttpTestClient.withCookies(webPort);
            assertEquals("OK", visitor.get("/health").body());
            Reply login = visitor.get("/login");
            assertEquals(200, login.status());
            assertTrue(login.body().contains("name=\"username\"") && login.body().contains("name=\"password\"") && login.body().contains("name=\"_csrf\""));
            HttpTestClient.assertRedirect(visitor.get("/dashboard"), "/login");

            HttpTestClient admin = HttpTestClient.withCookies(webPort);
            String token = HttpTestClient.extractCsrf(admin.get("/login").body());
            HttpTestClient.assertRedirect(admin.postForm("/login", Map.of("username", "boss", "password", adminPassword, "_csrf", token)), "/dashboard");
            Reply dashboard = admin.get("/dashboard");
            assertEquals(200, dashboard.status());
            assertTrue(dashboard.body().contains("Kitchen stations") && dashboard.body().contains("BIRYANI"));

            // an in-house order through the form, delivered by the real workers
            Reply menu = admin.get("/orders/new?brandCode=BIRYANI");
            Matcher item = Pattern.compile("name=\"itemId\" value=\"(\\d+)\"").matcher(menu.body());
            assertTrue(item.find(), "the menu lists items");
            String form = HttpTestClient.encode(Map.of("brandCode", "BIRYANI", "customerName", "Smoke Tester", "deliveryDistanceKm", "2",
                    "paymentMode", "CASH", "_csrf", HttpTestClient.extractCsrf(menu.body()))) + "&itemId=" + item.group(1) + "&quantity=1";
            Reply placed = admin.post("/orders/new", form);
            assertEquals(302, placed.status());
            String orderPath = placed.location().substring(placed.location().indexOf("/orders/view"));
            assertTrue(awaitBody(admin, orderPath, "DELIVERED", 60), "the order was delivered by the pipeline");
            Reply list = admin.get("/orders");
            assertTrue(list.body().contains("DELIVERED") && !list.body().contains("Smoke Tester"), "listed, without customer data");

            // sign out ends the session
            String logoutToken = HttpTestClient.extractCsrf(admin.get("/dashboard").body());
            HttpTestClient.assertRedirect(admin.postForm("/logout", Map.of("_csrf", logoutToken)), "/login");
            HttpTestClient.assertRedirect(admin.get("/dashboard"), "/login");

            child.destroy(); // SIGTERM: the JVM runs its shutdown hooks
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "the launcher must exit after SIGTERM");
            List<String> output = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            assertEquals(143, child.exitValue(), "128 + SIGTERM");
            List<String> steps = output.stream().filter(l -> l.contains("Shutdown step '")).toList();
            assertTrue(steps.size() >= 8, "every shutdown step ran: " + steps);
            assertTrue(output.stream().anyMatch(l -> l.contains("Shutdown finished") && l.contains("0 failed")), "no shutdown step failed: " + steps);
            assertTrue(output.stream().anyMatch(l -> l.contains("[ck-shutdown-hook]") && l.contains("Embedded database stopped")));
            assertTrue(output.stream().anyMatch(l -> l.contains("[ck-shutdown-hook]") && l.contains("Cloud Kitchen stopped")));
            // P8-D1: the database is stopped by the application after its own steps, so the final snapshot is written
            // and nothing finds the database gone
            assertTrue(steps.stream().anyMatch(l -> l.contains("write the final snapshot") && !l.contains("NOT WRITTEN")), "the final snapshot was written: " + steps);
            try (var snapshots = Files.list(root.resolve("snapshots"))) {
                List<String> names = snapshots.map(path -> path.getFileName().toString()).toList();
                assertTrue(names.stream().anyMatch(n -> n.endsWith(".ser")) && names.stream().anyMatch(n -> n.endsWith(".ser.hmac")), "a signed snapshot exists: " + names);
            }
            assertFalse(output.stream().anyMatch(l -> l.contains("Failed to validate connection") || l.contains(" WARN ")), "no warning expected: " + output.stream().filter(l -> l.contains(" WARN ")).toList());
            assertTrue(TestDatabase.serverProcessesForPort(dbPort).isEmpty(), "no database process may remain");
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", webPort).close(), "the web port must be closed");
            assertFalse(output.stream().anyMatch(l -> l.contains(" ERROR ")), "no ERROR line expected: " + output);
            List<String> orderLog = Files.readAllLines(root.resolve("logs").resolve("orders.log"), StandardCharsets.UTF_8);
            assertTrue(orderLog.stream().anyMatch(l -> l.contains("event=ORDER_DELIVERED")), "the order log recorded the delivery");
        } catch (Throwable failure) {
            keepLauncherLog(logFile);
            throw failure;
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
            TestDatabase.deleteTree(root);
        }
    }

    /** The child's output is the only diagnostic for a start-up that did not finish; keep it where a build leaves its reports. */
    private static void keepLauncherLog(Path logFile) {
        try {
            if (Files.exists(logFile)) {
                Path target = Path.of("target", "launcher-smoke-failure.log").toAbsolutePath();
                Files.createDirectories(target.getParent());
                Files.copy(logFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private static boolean awaitBody(HttpTestClient client, String path, String text, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (client.get(path).body().contains(text)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static int awaitPort(Process child, Path logFile, Pattern pattern, int seconds) throws Exception {
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
            if (!child.isAlive()) { // nothing more will be written: report the reason now instead of waiting for the bound
                throw new AssertionError("the launcher exited with " + child.exitValue() + " before '" + pattern + "' appeared; last lines: "
                        + tail(logFile));
            }
            Thread.sleep(200);
        }
        throw new AssertionError("pattern " + pattern + " not seen within " + seconds + " s in " + logFile + "; last lines: " + tail(logFile));
    }

    private static String tail(Path logFile) throws IOException {
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - 8), lines.size()));
    }
}
