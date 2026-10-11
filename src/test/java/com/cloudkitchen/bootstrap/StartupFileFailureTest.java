package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.EventLogException;
import com.cloudkitchen.exception.SnapshotWriteException;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-FIL-04: a log or snapshot path that cannot be used stops the start with the cause and leaves no thread or pool behind. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartupFileFailureTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static List<Thread> ownedThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                && (t.getName().startsWith("station-") || t.getName().equals("worker-monitor") || t.getName().equals("snapshot-scheduler")
                || t.getName().equals("recovery-loader") || t.getName().equals("reconcile-scheduler"))).toList();
    }

    private CloudKitchenException failing(String key, Path value) {
        Properties p = AppFixture.properties(h.db);
        p.setProperty(key, value.toString());
        return assertThrows(CloudKitchenException.class, () -> AppContext.create(AppConfig.fromProperties(p), h.db.jdbcUrl(), Clock.systemUTC(), null, false));
    }

    @Test
    void anUnusableOrderLogPathStopsTheStartWithAnEventLogException() throws Exception {
        Path blocker = Files.writeString(AppFixture.scratchDirectory().resolve("blocker"), "a file");
        CloudKitchenException e = failing("log.orders.path", blocker.resolve("orders.log"));
        assertInstanceOf(EventLogException.class, e);
        assertNotNull(e.getCause());
        assertEquals(List.of(), ownedThreads(), "nothing was left running");
    }

    @Test
    void anUnusableSnapshotDirectoryStopsTheStartWithASnapshotWriteExceptionAndClosesTheLog() throws Exception {
        Path blocker = Files.writeString(AppFixture.scratchDirectory().resolve("blocker"), "a file");
        Path logFile = AppFixture.scratchDirectory().resolve("logs").resolve("orders.log");
        Properties p = AppFixture.properties(h.db);
        p.setProperty("snapshot.dir", blocker.resolve("snapshots").toString());
        p.setProperty("log.orders.path", logFile.toString());
        CloudKitchenException e = assertThrows(CloudKitchenException.class,
                () -> AppContext.create(AppConfig.fromProperties(p), h.db.jdbcUrl(), Clock.systemUTC(), null, false));
        assertInstanceOf(SnapshotWriteException.class, e);
        assertNotNull(e.getCause());
        assertTrue(Files.exists(logFile), "the log had been opened before the snapshot directory failed");
        assertEquals(List.of(), ownedThreads(), "nothing was left running");
        Path fds = Path.of("/proc/self/fd");
        if (Files.isDirectory(fds)) { // Linux: no descriptor of this process may still point at the log file
            Path real = logFile.toRealPath();
            try (var open = Files.list(fds)) {
                long stillOpen = open.filter(fd -> {
                    try {
                        return Files.readSymbolicLink(fd).equals(real);
                    } catch (java.io.IOException e2) {
                        return false;
                    }
                }).count();
                assertEquals(0, stillOpen, "the log file was closed again");
            }
        }
        h.service.setAccepting(true);
    }
}
