package com.cloudkitchen.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.AppFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.RepeatedTest;

/** FR-FIL-01: 1,000 events from 16 threads give exactly 1,000 intact, non-interleaved lines, 20 times in a row. */
class OrderEventLogConcurrencyTest {

    private static final int THREADS = 16;
    private static final int EVENTS = 1000;
    private static final Pattern LINE = Pattern.compile(
            "ts=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z event=ORDER_ROUTED orderId=(\\d+) detail=\"t(\\d+) n(\\d+) [A-Za-z0-9 ]{40}\"");

    @RepeatedTest(20)
    void sixteenThreadsWriteExactlyOneThousandIntactLines() throws Exception {
        Path file = AppFixture.scratchDirectory().resolve("orders.log");
        NioOrderEventLog log = new NioOrderEventLog(file, Clock.systemUTC());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            int from = t * EVENTS / THREADS;
            int to = (t + 1) * EVENTS / THREADS;
            new Thread(() -> {
                try {
                    start.await();
                    for (int n = from; n < to; n++) {
                        // a long-ish detail makes a torn or interleaved line obvious
                        log.log(EventType.ORDER_ROUTED, n + 1L, "t" + thread + " n" + n + " " + "abcdefghij".repeat(4));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            }, "test-log-writer-" + t).start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        log.close();
        assertEquals(List.of(), errors);
        assertEquals(EVENTS, log.writtenCount());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(EVENTS, lines.size());
        Set<Long> ids = new HashSet<>();
        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            assertTrue(m.matches(), "an intact record: " + line);
            ids.add(Long.parseLong(m.group(1)));
        }
        assertEquals(EVENTS, ids.size(), "every event exactly once");
    }
}
