package com.cloudkitchen.pipeline;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates named, non-daemon threads whose uncaught errors are logged by class name only (SDD 7.1). A thread that ends
 * abnormally is reported here and, for station workers, detected by the {@code WorkerMonitor} through its future.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private static final Logger LOG = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String baseName;
    private final boolean numbered;
    private final AtomicInteger counter = new AtomicInteger();

    /** A factory for a pool of one thread (the name is used as is) or several (a number is appended). */
    public NamedThreadFactory(String baseName, boolean numbered) {
        this.baseName = Objects.requireNonNull(baseName, "baseName");
        this.numbered = numbered;
    }

    @Override
    public Thread newThread(Runnable task) {
        int n = counter.incrementAndGet();
        Thread thread = new Thread(task, numbered ? baseName + "-" + n : baseName);
        thread.setDaemon(false);
        thread.setUncaughtExceptionHandler((t, e) -> LOG.error("Thread {} ended with {}", t.getName(), e.getClass().getName()));
        return thread;
    }
}
