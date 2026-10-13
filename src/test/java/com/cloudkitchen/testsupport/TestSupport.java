package com.cloudkitchen.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared assertions and concurrency helpers (TESTING_STRATEGY section 3). */
public final class TestSupport {

    private TestSupport() {
    }

    /** Invariant I-4: no (from, to) edge appears twice in one order's history. */
    public static void assertEachEdgeOnce(DbTestSupport t, long orderId) throws SQLException {
        long duplicates = t.number("SELECT COUNT(*) FROM (SELECT from_status, to_status FROM order_status_history WHERE order_id = ?"
                + " GROUP BY from_status, to_status HAVING COUNT(*) > 1) d", orderId);
        assertEquals(0, duplicates, "a history edge occurs more than once for order " + orderId);
    }

    /** SDD 13.1.5: initial stock = remaining stock + quantities held by orders whose flag is 1. */
    public static void assertStockLedger(DbTestSupport t, long initialTotal) throws SQLException {
        long remaining = t.number("SELECT COALESCE(SUM(stock_qty), 0) FROM inventory");
        long held = t.number("SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.stock_deducted = 1");
        assertEquals(initialTotal, remaining + held, "stock ledger: remaining " + remaining + " + held " + held);
    }

    /** Runs {@code n} copies of the task at the same instant on named threads and returns their results. */
    public static <T> List<T> runConcurrently(int n, String threadName, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> new Thread(r, threadName));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "worker threads of the test did not end");
        }
    }

    /** Polls a condition without sleeping longer than 20 ms at a time; fails after the timeout. */
    public static void awaitCondition(String what, long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    /** No live thread whose name starts with the prefix (used after shutdown tests in later phases). */
    public static void assertNoLeakedThreads(String prefix) {
        List<String> leaked = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith(prefix)).map(Thread::getName).toList();
        assertEquals(List.of(), leaked);
    }
}
