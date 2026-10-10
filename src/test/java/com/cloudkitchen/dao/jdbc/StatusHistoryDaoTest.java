package com.cloudkitchen.dao.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusHistoryDaoTest {

    private FakeClock clock;
    private DbFixture db;
    private DbTestSupport t;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
        t = new DbTestSupport(db);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
    }

    private void insert(StatusHistoryEntry entry) throws Exception {
        db.database().inTransaction(c -> {
            t.history.insert(c, entry);
            return null;
        });
    }

    @Test
    void historyIsReadInOrderWithActorsAndNotes() throws Exception {
        long id = t.persist(t.biryaniOrder("H-1"));
        Instant t0 = clock.instant();
        insert(new StatusHistoryEntry(id, OrderStatus.PLACED, OrderStatus.VALIDATED, t0.plusSeconds(1), "SYSTEM", null));
        insert(new StatusHistoryEntry(id, OrderStatus.VALIDATED, OrderStatus.ROUTED, t0.plusSeconds(2), "worker:BIRYANI#2", "ROUTED_BY_WORKER"));
        insert(new StatusHistoryEntry(id, OrderStatus.ROUTED, OrderStatus.FAILED, t0.plusSeconds(2), "admin:alice", "n".repeat(255)));
        List<StatusHistoryEntry> rows = db.database().withConnection(c -> t.history.findByOrderId(c, id));
        assertEquals(4, rows.size());
        assertNull(rows.get(0).from());
        assertEquals(OrderStatus.PLACED, rows.get(0).to());
        assertEquals(List.of(OrderStatus.PLACED, OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.FAILED),
                rows.stream().map(StatusHistoryEntry::to).toList());
        assertEquals("worker:BIRYANI#2", rows.get(2).actor());
        assertEquals("ROUTED_BY_WORKER", rows.get(2).note());
        assertEquals(255, rows.get(3).note().length());
        assertEquals(t0.plusSeconds(1), rows.get(1).changedAt(), "millisecond UTC round trip");
        assertNull(rows.get(1).note());
    }

    @Test
    void theSameEdgeTwiceForOneOrderIsRejectedByTheUniqueIndex() throws Exception {
        long id = t.persist(t.biryaniOrder("H-2"));
        StatusHistoryEntry edge = new StatusHistoryEntry(id, OrderStatus.PLACED, OrderStatus.VALIDATED, clock.instant(), "SYSTEM", null);
        insert(edge);
        PersistenceException e = assertThrows(PersistenceException.class, () -> insert(edge));
        assertTrue(SqlErrors.isDuplicateKey(e), "uq_history_order_edge: " + e.getCause());
        assertEquals(2, t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", id));
        long other = t.persist(t.biryaniOrder("H-3"));
        insert(new StatusHistoryEntry(other, OrderStatus.PLACED, OrderStatus.VALIDATED, clock.instant(), "SYSTEM", null));
    }

    @Test
    void aDuplicateEdgeRollsBackTheWholeTransaction() throws Exception {
        long id = t.persist(t.biryaniOrder("H-4"));
        insert(new StatusHistoryEntry(id, OrderStatus.PLACED, OrderStatus.VALIDATED, clock.instant(), "SYSTEM", null));
        assertThrows(PersistenceException.class, () -> db.database().inTransaction(c -> {
            t.orders.transition(c, id, OrderStatus.PLACED, OrderStatus.VALIDATED, clock.instant());
            t.history.insert(c, new StatusHistoryEntry(id, OrderStatus.PLACED, OrderStatus.VALIDATED, clock.instant(), "SYSTEM", null));
            return null;
        }));
        assertEquals("PLACED", t.text("SELECT status FROM orders WHERE id = ?", id), "the status update rolled back with the failed insert");
    }

    @Test
    void historyNeedsAnExistingOrderAndAValidStatus() {
        SQLException e = assertThrows(SQLException.class, () ->
                t.execute("INSERT INTO order_status_history (order_id, from_status, to_status, changed_at, actor) VALUES (987654, NULL, 'PLACED', UTC_TIMESTAMP(3), 'x')"));
        assertEquals("23000", e.getSQLState());
        assertThrows(SQLException.class, () ->
                t.execute("INSERT INTO order_status_history (order_id, from_status, to_status, changed_at, actor) VALUES (1, NULL, 'NOPE', UTC_TIMESTAMP(3), 'x')"));
    }

    @Test
    void anOrderWithoutHistoryHasAnEmptyList() throws Exception {
        assertEquals(List.of(), db.database().withConnection(c -> t.history.findByOrderId(c, 555_555L)));
        assertTrue(clock.instant().isBefore(clock.instant().plus(Duration.ofMillis(1))));
    }
}
