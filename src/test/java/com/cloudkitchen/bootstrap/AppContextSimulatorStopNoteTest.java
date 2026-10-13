package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.pipeline.FeedSimulator;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The shutdown record of the simulator step must say what really happened: {@code FeedSimulator.shutdown()} returns
 * {@code false} both for a tick that cannot be stopped and for an interrupted caller, and the two must not be confused.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppContextSimulatorStopNoteTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private FeedSimulator simulator(FeedSimulator.OrderGenerator generator) {
        return new FeedSimulator(h.service, generator, 6000, 6000, h.clock, h.events, Duration.ofMillis(300));
    }

    private static boolean threadAlive() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals("feed-simulator"));
    }

    @Test
    void aSimulatorThatEndedNormallyHasNoNote() {
        FeedSimulator sim = simulator(seq -> Optional.empty());
        assertTrue(sim.start(6000));
        assertNull(AppContext.simulatorStopNote(sim));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @RepeatedTest(20)
    void aTickStuckInABlockedCallIsReportedAsABlockedSubmission() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FeedSimulator sim = simulator(seq -> {
            inside.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    if (release.await(30, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true; // ignored on purpose, like a call stuck in a driver
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        });
        try {
            assertTrue(sim.start(6000));
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            String note = AppContext.simulatorStopNote(sim);
            assertNotNull(note);
            assertTrue(note.contains("blocked submission"), note);
            assertFalse(note.contains("caller was interrupted"), note);
            assertFalse(Thread.currentThread().isInterrupted(), "the caller was not interrupted");
        } finally {
            release.countDown();
            sim.shutdown();
        }
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> !threadAlive()), "the thread ends once the call returns");
    }

    /** Starts a simulator whose tick ignores interrupts, like a call stuck in a driver, and waits until the tick is inside. */
    private FeedSimulator stuckSimulator(CountDownLatch release) throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        FeedSimulator sim = simulator(seq -> {
            inside.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    if (release.await(30, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        });
        assertTrue(sim.start(6000));
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        return sim;
    }

    /**
     * The thread is still alive when the interrupted caller waits for it, so {@code shutdown()} returns {@code false}
     * because of the interrupt (the case the old message mislabelled), deterministically.
     */
    @RepeatedTest(20)
    void anInterruptedCallerWhileATickIsStuckIsReportedAsInterruptedAndKeepsItsFlag() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FeedSimulator sim = stuckSimulator(release);
        String note;
        boolean flagAfter;
        Thread.currentThread().interrupt();
        try {
            note = AppContext.simulatorStopNote(sim);
            flagAfter = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
            release.countDown();
        }
        assertNotNull(note);
        assertTrue(note.contains("caller was interrupted"), note);
        assertFalse(note.contains("blocked submission"), note);
        assertTrue(flagAfter, "the flag is set again for the coordinator");
        assertTrue(sim.shutdown(), "once the call returned, an uninterrupted shutdown finds the thread ended");
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(10), () -> !threadAlive()));
    }

    /**
     * An idle simulator whose thread may or may not have ended by the time the interrupted caller waits for it: either
     * answer is true to the facts (no note, or the interrupted note), but never the blocked-submission note, and the
     * flag is always kept.
     */
    @RepeatedTest(20)
    void anInterruptedCallerOfAnIdleSimulatorNeverGetsTheBlockedSubmissionNote() {
        FeedSimulator sim = simulator(seq -> Optional.empty());
        assertTrue(sim.start(6000));
        Thread.currentThread().interrupt();
        String note;
        boolean flagAfter;
        try {
            note = AppContext.simulatorStopNote(sim);
            flagAfter = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }
        assertTrue(note == null || note.contains("caller was interrupted"), String.valueOf(note));
        assertTrue(flagAfter, "the flag is set again for the coordinator");
        assertTrue(sim.shutdown());
        assertFalse(sim.isRunning());
    }
}
