package com.cloudkitchen.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.EventLogException;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.FakeClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** SDD 8.1, FR-FIL-01, FR-FIL-04: the line format, sanitising, append-only behaviour, lifecycle and the failure paths of orders.log. */
class NioOrderEventLogTest {

    private final FakeClock clock = new FakeClock(Instant.parse("2026-10-10T06:41:12.345Z"));

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    void writesOneStructuredUtf8LinePerEventAndOmitsAnAbsentOrderId() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, clock);
        log.log(EventType.ORDER_VALIDATED, 42);
        log.log(EventType.ORDER_REJECTED, 0L, "VALIDATION");
        log.log(EventType.ORDER_FAILED, 7, "QUEUE_FULL");
        log.log(EventType.SIMULATOR_STARTED);
        log.close();
        assertEquals(List.of(
                "ts=2026-10-10T06:41:12.345Z event=ORDER_VALIDATED orderId=42",
                "ts=2026-10-10T06:41:12.345Z event=ORDER_REJECTED detail=\"VALIDATION\"",
                "ts=2026-10-10T06:41:12.345Z event=ORDER_FAILED orderId=7 detail=\"QUEUE_FULL\"",
                "ts=2026-10-10T06:41:12.345Z event=SIMULATOR_STARTED"), lines(file));
        assertEquals(4, log.writtenCount());
        assertEquals(0, log.failureCount());
    }

    @Test
    void sanitisesTheDetailSoThatOneEventIsAlwaysOneLine() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, clock);
        log.log(EventType.ORDER_REJECTED, 1, "a\r\nb\tc\u0000d e\u0085f \"quoted\" back\\slash");
        log.log(EventType.ORDER_REJECTED, 2, "x".repeat(500));
        log.close();
        List<String> written = lines(file);
        assertEquals(2, written.size(), "no detail can start another line");
        assertEquals("ts=2026-10-10T06:41:12.345Z event=ORDER_REJECTED orderId=1 detail=\"a  b c d e f \\\"quoted\\\" back\\\\slash\"", written.get(0));
        assertEquals("ts=2026-10-10T06:41:12.345Z event=ORDER_REJECTED orderId=2 detail=\"" + "x".repeat(200) + "\"", written.get(1), "capped at 200 characters");
    }

    @Test
    void createsMissingDirectoriesAndNeverTruncatesAnExistingFile() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("a").resolve("b").resolve("orders.log");
        NioOrderEventLog first = new NioOrderEventLog(file, clock);
        first.log(EventType.RECOVERY_STARTED);
        first.close();
        NioOrderEventLog second = new NioOrderEventLog(file, clock);
        second.log(EventType.RECOVERY_COMPLETED);
        second.close();
        assertEquals(List.of("ts=2026-10-10T06:41:12.345Z event=RECOVERY_STARTED", "ts=2026-10-10T06:41:12.345Z event=RECOVERY_COMPLETED"), lines(file));
    }

    @Test
    void closeIsIdempotentAndLaterEventsAreDroppedAndCountedNotThrown() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, clock);
        log.log(EventType.SHUTDOWN_STARTED);
        log.close();
        log.close();
        assertTrue(log.isClosed());
        log.log(EventType.SHUTDOWN_COMPLETED);
        assertEquals(1, log.droppedAfterCloseCount());
        assertEquals(1, lines(file).size());
        assertEquals(0, log.failureCount(), "a dropped event is not a failure");
    }

    @Test
    void aParentThatIsAFileIsReportedAsAnEventLogExceptionWithItsCause() throws Exception {
        Path dir = AppFixture.scratchDirectory();
        Path blocker = Files.writeString(dir.resolve("blocker"), "not a directory");
        EventLogException e = assertThrows(EventLogException.class, () -> new NioOrderEventLog(blocker.resolve("orders.log"), clock));
        assertEquals("EVENT_LOG", e.getCode());
        assertNotNull(e.getCause());
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(e.getMessage().contains("blocker"), "the message names the path");
    }

    @Test
    void aPathThatIsADirectoryCannotBeOpened() throws Exception {
        Path dir = AppFixture.scratchDirectory();
        EventLogException e = assertThrows(EventLogException.class, () -> new NioOrderEventLog(dir, clock));
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void aFailingWriteIsCountedAndReportedButNeverThrownIntoTheCaller() throws Exception {
        Path full = Path.of("/dev/full"); // every write fails with "no space left on device"
        Assumptions.assumeTrue(Files.exists(full), "/dev/full exists on Linux");
        NioOrderEventLog log = new NioOrderEventLog(full, clock);
        log.log(EventType.ORDER_VALIDATED, 1);
        log.log(EventType.ORDER_VALIDATED, 2);
        assertEquals(0, log.writtenCount());
        assertEquals(2, log.failureCount());
        assertNotNull(log.lastFailure());
        log.close();
    }

    @RepeatedTest(20)
    void aCallerWithItsInterruptFlagSetDoesNotCloseTheLogForEverybodyAndKeepsItsFlag() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, clock);
        CountDownLatch done = new CountDownLatch(1);
        boolean[] flagAfter = new boolean[1];
        Thread interrupted = new Thread(() -> {
            Thread.currentThread().interrupt();
            log.log(EventType.WORKER_DIED, 0L, "from an interrupted thread");
            flagAfter[0] = Thread.currentThread().isInterrupted();
            done.countDown();
        }, "test-interrupted-logger");
        interrupted.start();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertTrue(flagAfter[0], "the flag was restored");
        log.log(EventType.WORKER_RESTARTED);
        log.close();
        assertEquals(2, lines(file).size(), "the channel survived: both events are there");
        assertEquals(0, log.failureCount());
    }

    @RepeatedTest(20)
    void anInterruptDeliveredDuringTheWriteClosesTheChannelButTheEventIsWrittenAfterAReopenAndTheFlagIsRestored() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, clock);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean();
        log.setBeforeWrite(() -> {
            if (first.compareAndSet(false, true)) {
                Thread.currentThread().interrupt(); // what another thread's interrupt() does while this one is writing
            }
        });
        boolean[] flagAfter = new boolean[1];
        Thread writer = new Thread(() -> {
            log.log(EventType.ORDER_DELIVERED, 5);
            flagAfter[0] = Thread.currentThread().isInterrupted();
        }, "test-interrupted-writer");
        writer.start();
        writer.join(20_000);
        assertFalse(writer.isAlive());
        assertTrue(flagAfter[0], "the interrupt is not swallowed");
        log.log(EventType.ORDER_DELIVERED, 6);
        log.close();
        assertEquals(2, lines(file).size(), "both events are in the file");
        assertEquals(2, log.writtenCount());
        assertEquals(0, log.failureCount());
    }

    @RepeatedTest(20)
    void aChannelClosedByAnInterruptDuringTheWriteIsReopenedAndTheEventIsStillWritten() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, clock);
        log.log(EventType.RECOVERY_STARTED);
        log.channelForTest().close(); // what a ClosedByInterruptException leaves behind
        log.log(EventType.RECOVERY_COMPLETED);
        log.close();
        assertEquals(2, lines(file).size());
        assertEquals(2, log.writtenCount());
        assertFalse(log.failureCount() > 0, "the retry succeeded, so nothing was lost");
    }
}
