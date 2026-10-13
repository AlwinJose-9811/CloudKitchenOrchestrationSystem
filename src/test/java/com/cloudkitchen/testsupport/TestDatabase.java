package com.cloudkitchen.testsupport;

import com.cloudkitchen.bootstrap.DbBootstrap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Helpers for tests that need a real embedded database: free port, project-local directories, process checks. */
public final class TestDatabase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestDatabase() {
    }

    /** Settings with a private directory under ./data/test, a free port and a random password (never logged). */
    public static DbBootstrap.Settings newSettings(String name) {
        Path data = Path.of("data").toAbsolutePath();
        Path root = data.resolve("test").resolve(name + "-" + randomHex(4));
        return new DbBootstrap.Settings(data.resolve("runtime"), root.resolve("db"), root.resolve("tmp"),
                root.resolve("run").resolve("db.sock"), freePort(), "ckdb", "ck_app", randomHex(16));
    }

    /** The per-test directory that {@link #newSettings} created (parent of the data directory). */
    public static Path rootOf(DbBootstrap.Settings settings) {
        return settings.dataDir().getParent();
    }

    public static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Database server processes started for the given port (matched on the command line). */
    public static List<ProcessHandle> serverProcessesForPort(int port) {
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info().commandLine()
                        .map(line -> line.contains("mariadbd") && line.contains("--port=" + port))
                        .orElse(false))
                .toList();
    }

    public static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
