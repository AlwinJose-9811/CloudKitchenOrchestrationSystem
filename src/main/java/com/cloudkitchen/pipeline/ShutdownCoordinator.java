package com.cloudkitchen.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the ordered shutdown steps exactly once (SDD 7.6, FR-RES-04), whoever asks first: the servlet listener, the
 * JVM shutdown hook, a test or an operator. The first caller runs every step in order, each in its own try/catch (a
 * failing step is logged and the next step still runs, because the pool and the files must be closed anyway); any other
 * caller blocks until the first has finished, for at most {@code waitBound}. Calling it again afterwards returns at once.
 *
 * <p>Interrupts: the steps are bounded by their own timeouts. If the thread running the steps is interrupted, the flag
 * is cleared between steps so that the remaining steps can still close their resources, and it is set again before
 * {@link #shutdown()} returns, as is the flag of a thread that was interrupted while waiting for the first caller.
 *
 * <p>Thread ownership: it creates no thread except the optional JVM hook {@code ck-shutdown-hook}.
 */
public final class ShutdownCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownCoordinator.class);

    /** One shutdown step; the returned text (may be {@code null}) is recorded as its outcome. */
    @FunctionalInterface
    public interface Step {
        String run() throws Exception;
    }

    /** How one step ended. */
    public record StepResult(String name, long millis, boolean failed, String note) {
    }

    /** How the whole shutdown ended; an immutable copy. */
    public record Outcome(boolean complete, long totalMillis, List<StepResult> steps) {
        public boolean anyFailed() {
            return steps.stream().anyMatch(StepResult::failed);
        }
    }

    private record Named(String name, Step step) {
    }

    private final List<Named> steps = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch done = new CountDownLatch(1);
    private final Duration waitBound;
    private volatile Outcome outcome;
    private Thread hook;

    public ShutdownCoordinator(Duration waitBound) {
        this.waitBound = Objects.requireNonNull(waitBound, "waitBound");
    }

    /** Appends a step; steps run in registration order. Registering after the shutdown started has no effect. */
    public void register(String name, Step step) {
        steps.add(new Named(Objects.requireNonNull(name, "name"), Objects.requireNonNull(step, "step")));
    }

    /** Registers the JVM shutdown hook (thread {@code ck-shutdown-hook}); a second call does nothing. */
    public synchronized void installJvmHook() {
        if (hook != null) {
            return;
        }
        hook = new Thread(this::shutdown, "ck-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /** Whether the JVM hook is currently registered (it is removed again when the shutdown ran normally). */
    public synchronized boolean jvmHookInstalled() {
        return hook != null;
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    /** The outcome once the shutdown finished, otherwise {@code null}. */
    public Outcome outcome() {
        return outcome;
    }

    /**
     * Runs the steps once (the first caller) or waits for the first caller to finish (every other caller).
     *
     * @return {@code true} if the shutdown is complete when this call returns; {@code false} only for a waiting caller
     *         that gave up (timeout or interrupt) while the first caller was still running its steps
     */
    public boolean shutdown() {
        if (!started.compareAndSet(false, true)) {
            return awaitFirstCaller();
        }
        long begin = System.nanoTime();
        boolean interrupted = false;
        List<StepResult> results = new ArrayList<>();
        try {
            for (Named named : steps) {
                interrupted |= Thread.interrupted(); // let the remaining steps close their resources
                long stepBegin = System.nanoTime();
                try {
                    String note = named.step().run();
                    results.add(new StepResult(named.name(), millisSince(stepBegin), false, note));
                    LOG.info("Shutdown step '{}' done in {} ms{}", named.name(), millisSince(stepBegin), note == null ? "" : " (" + note + ")");
                } catch (Exception | Error e) {
                    results.add(new StepResult(named.name(), millisSince(stepBegin), true, e.getClass().getName()));
                    LOG.error("Shutdown step '{}' failed: {}", named.name(), e.getClass().getName());
                }
            }
        } finally {
            outcome = new Outcome(true, millisSince(begin), List.copyOf(results));
            LOG.info("Shutdown finished in {} ms ({} steps, {} failed)", outcome.totalMillis(), results.size(),
                    results.stream().filter(StepResult::failed).count());
            removeHook();
            done.countDown();
            interrupted |= Thread.interrupted();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    private boolean awaitFirstCaller() {
        try {
            if (!done.await(waitBound.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Shutdown started by another thread did not finish within {} ms", waitBound.toMillis());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized void removeHook() {
        if (hook == null || Thread.currentThread() == hook) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // the JVM is already shutting down: the hook is running or about to run and finds the work done
        }
        hook = null;
    }

    private static long millisSince(long beginNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
    }
}
