package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

/**
 * Phase 8 defect P8-D4: the order page read the order row and its status history in separate statements, so an order that
 * advanced in between made {@code Order} refuse the pair ("history ends in PREPARING but the status is ROUTED") and the page
 * answered 500. The three reads of {@code detail} must see one snapshot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderDetailConsistentReadTest {

    private static final int ORDERS = 45; // the seeded stock of one item is 50
    /** {@code submitOrder} leaves an accepted order in ROUTED; the writer walks it on, as a worker would. */
    private static final OrderStatus[] PATH = {OrderStatus.ROUTED, OrderStatus.PREPARING, OrderStatus.PACKED,
            OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED};

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @BeforeEach
    void clean() throws Exception {
        h.reset();
    }

    @RepeatedTest(20)
    void readingAnOrderWhileItAdvancesNeverSeesAnInconsistentPair() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            OrderRequest request = h.t.request(OrderSource.SWIGGY, "CONSISTENT-" + i, "BIRYANI", new Object[] {"Chicken Biryani", 1});
            ids.add(h.service.submitOrder(request).orderId());
        }
        OrderQueryService query = new OrderQueryService(h.t.db.database(), h.t.orders);
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicLong reads = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readers.add(pool.submit(() -> {
                    go.await();
                    int next = 0;
                    while (writing.get()) {
                        Order seen = query.detail(ids.get(next++ % ORDERS)).orElseThrow();
                        assertEquals(seen.getStatus(), seen.getHistory().get(seen.getHistory().size() - 1).to());
                        reads.incrementAndGet();
                    }
                    return null;
                }));
            }
            Future<?> writer = pool.submit(() -> {
                go.await();
                try {
                    for (int step = 1; step < PATH.length; step++) {
                        for (int i = 0; i < ORDERS; i++) {
                            assertTrue(h.service.transition(ids.get(i), PATH[step - 1], PATH[step], "test", null));
                        }
                    }
                } finally {
                    writing.set(false);
                }
                return null;
            });
            go.countDown();
            writer.get(120, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(60, TimeUnit.SECONDS);
            }
            assertTrue(reads.get() > 0, "the readers ran while the writer advanced the orders");
        } finally {
            pool.shutdownNow();
        }
    }
}
