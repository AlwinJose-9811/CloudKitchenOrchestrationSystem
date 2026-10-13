package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.SnapshotWriteException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-RES-05, SDD F-17: the periodic task writes and prunes, survives a failing run, and stops cleanly. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotTaskTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static boolean schedulerAlive() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals("snapshot-scheduler"));
    }

    @RepeatedTest(20)
    void everyIntervalAWrittenAndPrunedSnapshotAppearsUpToTheRetentionLimit() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 2);
        f.submit("ST-1", "BIRYANI", "Raita", 1);
        SnapshotTask task = new SnapshotTask(f.service::writeAndPrune, 20, TimeUnit.MILLISECONDS, Clock.systemUTC());
        task.start();
        task.start(); // a second start does nothing
        try {
            assertTrue(h.events.awaitCount(EventType.SNAPSHOT_WRITTEN, null, 4, Duration.ofSeconds(60)), "four intervals produced four snapshots");
        } finally {
            task.stop();
        }
        task.stop(); // idempotent
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> !schedulerAlive()), "the scheduler thread ended");
        assertTrue(task.stats().runs() >= 4);
        assertEquals(0, task.stats().failures());
        assertNotNull(task.stats().lastSuccessAt());
        assertEquals(2, f.serFiles().size(), "only the newest two pairs are kept");
        for (Path ser : f.serFiles()) {
            assertEquals(1, f.service.read(ser, SnapshotFixture.signatureOf(ser)).size());
        }
    }

    @RepeatedTest(20)
    void aFailingRunIsCountedAndTheNextIntervalRunsAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch thirdRun = new CountDownLatch(1);
        SnapshotTask task = new SnapshotTask(() -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new SnapshotWriteException("disk full", new java.io.IOException("no space left"));
            }
            if (n == 2) {
                throw new IllegalStateException("unexpected");
            }
            thirdRun.countDown();
        }, 20, TimeUnit.MILLISECONDS, Clock.systemUTC());
        task.start();
        try {
            assertTrue(thirdRun.await(60, TimeUnit.SECONDS), "the task kept running after two failures");
        } finally {
            task.stop();
        }
        SnapshotTask.Stats stats = task.stats();
        assertTrue(stats.runs() >= 3);
        assertEquals(2, stats.failures());
        assertNotNull(stats.lastSuccessAt());
        assertEquals("IllegalStateException", stats.lastFailure());
    }

    @Test
    void aSnapshotThatCannotBeWrittenLeavesNoFinalFileAndIsReportedWithItsCause() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        f.submit("WF-1", "BIRYANI", "Raita", 1);
        Files.delete(f.dir);
        Files.writeString(f.dir, "now a file, not a directory");
        SnapshotWriteException e = assertThrows(SnapshotWriteException.class, () -> f.service.writeSnapshot());
        assertEquals("SNAPSHOT_WRITE", e.getCode());
        assertNotNull(e.getCause());
        assertEquals(0, h.events.count(EventType.SNAPSHOT_WRITTEN));
        assertTrue(Files.isRegularFile(f.dir), "nothing was created inside");
    }

    @Test
    void aSnapshotLargerThanTheReadLimitIsNotWrittenBecauseItCouldNotBeReadBack() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 200, 10);
        f.submit("BG-1", "BIRYANI", "Raita", 1);
        SnapshotWriteException e = assertThrows(SnapshotWriteException.class, () -> f.service.writeSnapshot());
        assertTrue(e.getMessage().contains("snapshot.max.bytes"), e.getMessage());
        assertEquals(0, f.serFiles().size());
    }

    @Test
    void theDirectoryIsCreatedAtConstructionAndAnImpossiblePathFailsStartupWithTheCause() throws Exception {
        Path blocker = Files.writeString(AppFixture.scratchDirectory().resolve("blocker"), "a file");
        SnapshotWriteException e = assertThrows(SnapshotWriteException.class, () -> new SnapshotService(h.db.database(), h.t.orders, h.t.history,
                blocker.resolve("snapshots"), new HmacSigner(SnapshotFixture.SECRET), 1024, 1, h.clock, h.events));
        assertNotNull(e.getCause());
        assertFalse(schedulerAlive());
    }
}
