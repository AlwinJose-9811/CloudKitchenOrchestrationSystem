package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** SDD 7.6: ordered steps, each step guarded, run exactly once however many threads ask (listener, JVM hook, tests). */
class ShutdownCoordinatorTest {

    @Test
    void stepsRunInRegistrationOrderAndAFailingStepDoesNotStopTheOthers() {
        ShutdownCoordinator c = new ShutdownCoordinator(Duration.ofSeconds(5));
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        c.register("one", () -> {
            ran.add("one");
            return "first";
        });
        c.register("boom", () -> {
            ran.add("boom");
            throw new IllegalStateException("step failed");
        });
        c.register("three", () -> {
            ran.add("three");
            return null;
        });
        assertTrue(c.shutdown());
        assertEquals(List.of("one", "boom", "three"), ran);
        ShutdownCoordinator.Outcome outcome = c.outcome();
        assertTrue(outcome.complete() && outcome.anyFailed());
        assertEquals(List.of(false, true, false), outcome.steps().stream().map(ShutdownCoordinator.StepResult::failed).toList());
        assertEquals("first", outcome.steps().get(0).note());
        assertEquals(IllegalStateException.class.getName(), outcome.steps().get(1).note());
        assertTrue(c.isDone());
    }

    @RepeatedTest(20)
    void sixteenThreadsIncludingAThreadCalledLikeTheJvmHookRunEachStepOnce() throws Exception {
        ShutdownCoordinator c = new ShutdownCoordinator(Duration.ofSeconds(20));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch insideStep = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        c.register("slow step", () -> {
            runs.incrementAndGet();
            insideStep.countDown();
            releaseStep.await(20, TimeUnit.SECONDS);
            return null;
        });
        ExecutorService executor = Executors.newFixedThreadPool(16, r -> new Thread(r, "test-shutdown-caller"));
        try {
            CyclicBarrier barrier = new CyclicBarrier(16);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return c.shutdown();
                }));
            }
            assertTrue(insideStep.await(10, TimeUnit.SECONDS));
            assertFalse(c.isDone(), "the first caller is still inside its step, the others wait");
            releaseStep.countDown();
            for (Future<Boolean> f : results) {
                assertTrue(f.get(20, TimeUnit.SECONDS), "every caller returns only after the shutdown is complete");
            }
            assertEquals(1, runs.get());
            assertTrue(c.shutdown(), "a later call returns at once");
            assertEquals(1, runs.get());
        } finally {
            releaseStep.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aWaitingCallerGivesUpAfterTheBoundWhileTheFirstCallerIsStillRunning() throws Exception {
        ShutdownCoordinator c = new ShutdownCoordinator(Duration.ofMillis(100));
        CountDownLatch insideStep = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        c.register("stuck step", () -> {
            insideStep.countDown();
            releaseStep.await(20, TimeUnit.SECONDS);
            return null;
        });
        Thread first = new Thread(c::shutdown, "test-first-caller");
        first.start();
        try {
            assertTrue(insideStep.await(10, TimeUnit.SECONDS));
            assertFalse(c.shutdown(), "the second caller waited for the bound and then reported that it is not complete");
            assertFalse(c.isDone());
        } finally {
            releaseStep.countDown();
            first.join(10_000);
        }
        assertTrue(c.isDone());
    }

    @Test
    void aWaitingCallerThatIsInterruptedKeepsItsInterruptFlag() throws Exception {
        ShutdownCoordinator c = new ShutdownCoordinator(Duration.ofSeconds(30));
        CountDownLatch insideStep = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        c.register("stuck step", () -> {
            insideStep.countDown();
            releaseStep.await(20, TimeUnit.SECONDS);
            return null;
        });
        Thread first = new Thread(c::shutdown, "test-first-caller");
        first.start();
        try {
            assertTrue(insideStep.await(10, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            assertFalse(c.shutdown(), "interrupted while waiting");
            assertTrue(Thread.interrupted(), "and the flag was restored");
        } finally {
            releaseStep.countDown();
            first.join(10_000);
        }
    }

    @Test
    void aStepThatIsInterruptedDoesNotPreventTheLaterStepsAndTheFlagIsRestoredAtTheEnd() throws Exception {
        ShutdownCoordinator c = new ShutdownCoordinator(Duration.ofSeconds(5));
        List<String> ran = new ArrayList<>();
        c.register("restores the flag", () -> {
            ran.add("a");
            Thread.currentThread().interrupt(); // what a step does when its wait was interrupted
            return null;
        });
        c.register("must still run", () -> {
            ran.add("b");
            assertFalse(Thread.currentThread().isInterrupted(), "the pool and the files can still be closed");
            return null;
        });
        assertTrue(c.shutdown());
        assertEquals(List.of("a", "b"), ran);
        assertTrue(Thread.interrupted(), "the caller's flag is set again when the shutdown returns");
    }

    @Test
    void theJvmHookIsRegisteredOnceAndRemovedAgainWhenTheShutdownRanNormally() {
        ShutdownCoordinator c = new ShutdownCoordinator(Duration.ofSeconds(5));
        assertFalse(c.jvmHookInstalled());
        c.installJvmHook();
        c.installJvmHook(); // a second installation does nothing
        assertTrue(c.jvmHookInstalled());
        c.register("noop", () -> null);
        c.shutdown();
        assertFalse(c.jvmHookInstalled(), "no hook is left behind in a long-running JVM or a test run");
    }
}
