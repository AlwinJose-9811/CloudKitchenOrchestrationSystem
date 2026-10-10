package com.cloudkitchen.dao.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dto.StockRow;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockDaoTest {

    private DbFixture db;
    private DbTestSupport t;
    private long item;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(new FakeClock());
        t = new DbTestSupport(db);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
        item = t.itemId("Chicken Biryani");
    }

    private boolean deduct(long itemId, int qty) throws Exception {
        return Boolean.TRUE.equals(db.database().inTransaction(c -> t.stock.deduct(c, itemId, qty)));
    }

    @Test
    void deductionSucceedsDownToExactlyZeroAndThenFails() throws Exception {
        assertTrue(deduct(item, 49));
        assertEquals(1, t.stockOf("Chicken Biryani"));
        assertFalse(deduct(item, 2), "one short: nothing changes");
        assertEquals(1, t.stockOf("Chicken Biryani"));
        assertTrue(deduct(item, 1), "the boundary: stock >= qty");
        assertEquals(0, t.stockOf("Chicken Biryani"));
        assertFalse(deduct(item, 1));
        assertEquals(0, t.stockOf("Chicken Biryani"), "never negative");
    }

    @Test
    void anUnknownItemOrANonPositiveQuantityIsNotDeducted() throws Exception {
        assertFalse(deduct(9_999_999L, 1));
        assertThrows(IllegalArgumentException.class, () -> deduct(item, 0));
        assertThrows(IllegalArgumentException.class, () -> deduct(item, -3));
    }

    @Test
    void restoreAddsBackAndRejectsAnUnknownItem() throws Exception {
        deduct(item, 10);
        db.database().inTransaction(c -> {
            t.stock.restore(c, item, 4);
            return null;
        });
        assertEquals(44, t.stockOf("Chicken Biryani"));
        PersistenceException e = assertThrows(PersistenceException.class, () -> db.database().inTransaction(c -> {
            t.stock.restore(c, 9_999_999L, 1);
            return null;
        }));
        assertTrue(e.getCause() instanceof SQLException);
    }

    @Test
    void theDatabaseItselfRefusesNegativeStock() {
        assertThrows(SQLException.class, () -> t.execute("UPDATE inventory SET stock_qty = -1 WHERE item_id = ?", item));
        assertThrows(SQLException.class, () -> t.execute("UPDATE inventory SET stock_qty = stock_qty - 51 WHERE item_id = ?", item));
        assertThrows(SQLException.class, () -> t.execute("UPDATE inventory SET reorder_level = -1 WHERE item_id = ?", item));
    }

    @Test
    void theLowStockFlagIsStockAtOrBelowTheReorderLevel() throws Exception {
        assertEquals(0, db.database().withConnection(c -> t.stock.findLow(c)).size(), "50 > 10");
        deduct(item, 39);
        assertEquals(0, db.database().withConnection(c -> t.stock.findLow(c)).size(), "11 is above 10");
        deduct(item, 1);
        List<StockRow> low = db.database().withConnection(c -> t.stock.findLow(c));
        assertEquals(1, low.size(), "10 equals the reorder level: low");
        assertEquals("Chicken Biryani", low.get(0).itemName());
        assertEquals("BIRYANI", low.get(0).brandCode());
        assertTrue(low.get(0).low());
        List<StockRow> all = db.database().withConnection(c -> t.stock.findAll(c));
        assertEquals(15, all.size());
        assertEquals(1, all.stream().filter(StockRow::low).count());
    }

    @Test
    void setCreateAndFind() throws Exception {
        db.database().inTransaction(c -> {
            t.stock.set(c, item, 7, 3);
            return null;
        });
        var level = db.database().withConnection(c -> t.stock.find(c, item)).orElseThrow();
        assertEquals(7, level.getStockQty());
        assertEquals(3, level.getReorderLevel());
        assertTrue(db.database().withConnection(c -> t.stock.find(c, 9_999_999L)).isEmpty());
        assertThrows(PersistenceException.class, () -> db.database().inTransaction(c -> {
            t.stock.set(c, 9_999_999L, 1, 1);
            return null;
        }));
        long newItem = db.database().inTransaction(c -> t.menu.insert(c, t.station("PIZZA").getId(), "Calzone", new java.math.BigDecimal("349.00"), true));
        db.database().inTransaction(c -> {
            t.stock.create(c, newItem, 20, 5);
            return null;
        });
        assertEquals(20, db.database().withConnection(c -> t.stock.find(c, newItem)).orElseThrow().getStockQty());
        assertThrows(PersistenceException.class, () -> db.database().inTransaction(c -> {
            t.stock.create(c, newItem, 1, 1);
            return null;
        }), "one inventory row per item (primary key)");
    }

    @Test
    void anInventoryRowNeedsAMenuItem() {
        assertThrows(SQLException.class, () -> t.execute("INSERT INTO inventory (item_id, stock_qty, reorder_level, updated_at) VALUES (?, 1, 1, UTC_TIMESTAMP(3))", 8_888_888L));
    }

    @RepeatedTest(20)
    void competingDeductionsSellExactlyTheAvailableUnits() throws Exception {
        db.resetData();
        t.execute("UPDATE inventory SET stock_qty = 10 WHERE item_id = ?", item);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(r, "stock-race"));
        try {
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger sold = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    if (deduct(item, 1)) {
                        sold.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            assertEquals(10, sold.get(), "N threads competing for K units sell exactly K");
            assertEquals(0, t.stockOf("Chicken Biryani"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
