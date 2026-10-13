package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.pipeline.RecoveryService;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-SER-05, SDD 9.4: only orders missing from the database are inserted; the database wins every conflict; repeating is harmless. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotRecoveryTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private void removeOrder(long id) throws Exception {
        h.t.execute("DELETE FROM order_status_history WHERE order_id = ?", id);
        h.t.execute("DELETE FROM order_items WHERE order_id = ?", id);
        h.t.execute("DELETE FROM orders WHERE id = ?", id);
    }

    @Test
    void ofThreeSnapshotOrdersExactlyTheMissingOneIsInsertedAndRepeatingInsertsNothing() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long a = f.submit("SR-A", "BIRYANI", "Chicken Biryani", 2, "Raita", 1);
        long b = f.submit("SR-B", "BURGER", "Classic Burger", 1);
        long c = f.submit("SR-C", "BIRYANI", "Raita", 3);
        f.service.writeSnapshot();
        String trackingOfB = h.t.text("SELECT tracking_code FROM orders WHERE id = ?", b);
        removeOrder(b);
        assertEquals(2, h.t.number("SELECT COUNT(*) FROM orders"));

        assertEquals(1, f.service.recoverMissingOrders());
        assertEquals(new SnapshotService.Recovery(true, 1, 2, 0, 0), f.service.lastRecovery());
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM orders"));
        long restored = h.t.number("SELECT id FROM orders WHERE source = 'SWIGGY' AND external_ref = 'SR-B'");
        assertEquals("ROUTED", h.t.text("SELECT status FROM orders WHERE id = ?", restored));
        assertEquals(0, h.t.number("SELECT stock_deducted FROM orders WHERE id = ?", restored), "the stock state of a lost database cannot be known");
        assertEquals(trackingOfB, h.t.text("SELECT tracking_code FROM orders WHERE id = ?", restored));
        assertEquals("Asha Rao", h.t.text("SELECT customer_name FROM orders WHERE id = ?", restored));
        assertEquals(1, h.t.number("SELECT COUNT(*) FROM order_items WHERE order_id = ?", restored));
        assertEquals(List.of("PLACED:null", "VALIDATED:PLACED", "ROUTED:VALIDATED"), historyOf(restored));
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND note = 'RECOVERED_FROM_SNAPSHOT'", restored));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND note = 'RECOVERED_FROM_SNAPSHOT'", a), "the untouched orders keep their own history");

        assertEquals(0, f.service.recoverMissingOrders(), "a second start finds everything present");
        assertEquals(0, f.service.recoverMissingOrders());
        assertEquals(3, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(new SnapshotService.Recovery(true, 0, 3, 0, 0), f.service.lastRecovery());
        assertTrue(a > 0 && c > 0);
    }

    private List<String> historyOf(long orderId) throws Exception {
        List<String> out = new ArrayList<>();
        try (var c = h.db.connection(); var p = c.prepareStatement("SELECT to_status, from_status FROM order_status_history WHERE order_id = ? ORDER BY id")) {
            p.setLong(1, orderId);
            try (var rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1) + ":" + rs.getString(2));
                }
            }
        }
        return out;
    }

    @Test
    void anOrderThatExistsInTheDatabaseIsNeverOverwrittenAndADifferentStatusIsCountedAsAConflict() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long a = f.submit("CF-A", "BIRYANI", "Raita", 1);
        long b = f.submit("CF-B", "BIRYANI", "Raita", 2);
        f.service.writeSnapshot();
        h.service.transition(a, OrderStatus.ROUTED, OrderStatus.PREPARING, "TEST", null); // the database moved on after the snapshot
        h.t.execute("UPDATE orders SET customer_name = 'Changed Later' WHERE id = ?", b);
        assertEquals(0, f.service.recoverMissingOrders());
        assertEquals(new SnapshotService.Recovery(true, 0, 1, 1, 0), f.service.lastRecovery());
        assertEquals("PREPARING", h.t.text("SELECT status FROM orders WHERE id = ?", a), "the database row is untouched");
        assertEquals("Changed Later", h.t.text("SELECT customer_name FROM orders WHERE id = ?", b));
        assertEquals(2, h.t.number("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void anInvalidOrderAndATrackingCodeCollisionAreSkippedAndTheOthersAreStillRestored() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long existing = f.submit("IV-0", "BIRYANI", "Raita", 1);
        String usedCode = h.t.text("SELECT tracking_code FROM orders WHERE id = ?", existing);
        OrderSnapshotDTO good = SnapshotFixture.dto(901, "IV-GOOD", "PREPARING");
        OrderSnapshotDTO unknownStation = withStation(SnapshotFixture.dto(902, "IV-STATION", "ROUTED"), 999_999L);
        OrderSnapshotDTO terminal = SnapshotFixture.dto(903, "IV-DONE", "DELIVERED");
        OrderSnapshotDTO badMoney = withPrice(SnapshotFixture.dto(904, "IV-MONEY", "ROUTED"), "not a number");
        OrderSnapshotDTO collision = withTracking(SnapshotFixture.dto(905, "IV-CODE", "ROUTED"), usedCode);
        OrderSnapshotDTO noLines = new OrderSnapshotDTO(1, 906, "SWIGGY", "IV-EMPTY", "TRK000000906", 1L, "BIRYANI", "ROUTED", "Test Person", "6.10",
                "PREPAID_AGGREGATOR", "PAID", "1.00", "10.00", 1_760_000_000_000L, 1_760_001_000_000L, true, List.of());
        OrderSnapshotDTO unknownItem = new OrderSnapshotDTO(1, 907, "SWIGGY", "IV-ITEM", "TRK000000907", 1L, "BIRYANI", "ROUTED", "Test Person", "6.10",
                "PREPAID_AGGREGATOR", "PAID", "1.00", "10.00", 1_760_000_000_000L, 1_760_001_000_000L, true,
                List.of(new OrderSnapshotDTO.ItemLine(987_654L, "Ghost", "10.00", 1)));
        f.craftSigned(SnapshotFixture.serialise(new ArrayList<>(List.of(unknownStation, terminal, badMoney, collision, noLines, unknownItem, good))));

        assertEquals(1, f.service.recoverMissingOrders());
        assertEquals(new SnapshotService.Recovery(true, 1, 0, 1, 5), f.service.lastRecovery());
        assertEquals(2, h.t.number("SELECT COUNT(*) FROM orders"), "the existing order and the one good order");
        assertEquals("PREPARING", h.t.text("SELECT status FROM orders WHERE external_ref = 'IV-GOOD'"));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders WHERE external_ref IN ('IV-STATION','IV-DONE','IV-MONEY','IV-CODE','IV-EMPTY','IV-ITEM')"));
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM order_items WHERE order_id NOT IN (SELECT id FROM orders)"), "the failed transactions left nothing behind");
    }

    @Test
    void aFailedDatabaseWriteSkipsThatOrderOnlyAndTheNextStartRestoresIt() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, h.orderFaults.dao(), 5_242_880, 10);
        long a = f.submit("DB-A", "BIRYANI", "Raita", 1);
        long b = f.submit("DB-B", "BURGER", "Classic Burger", 1);
        f.service.writeSnapshot();
        removeOrder(a);
        removeOrder(b);
        h.orderFaults.resetCounters();
        h.orderFaults.failBefore("insertRecovered", 1, new java.sql.SQLException("disk problem", "HY000", 9999));
        assertEquals(1, f.service.recoverMissingOrders(), "the first order failed, the second was restored");
        assertEquals(1, h.t.number("SELECT COUNT(*) FROM orders"));
        h.orderFaults.clearFaults();
        assertEquals(1, f.service.recoverMissingOrders(), "the next start restores the one that failed");
        assertEquals(2, h.t.number("SELECT COUNT(*) FROM orders"));
        assertEquals(0, f.service.recoverMissingOrders());
    }

    @Test
    void aMissingTamperedOrOldSnapshotNeverPreventsStartAndInsertsNothing() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long id = f.submit("NS-1", "BIRYANI", "Raita", 1);
        assertEquals(0, f.service.recoverMissingOrders(), "no snapshot");
        assertEquals(new SnapshotService.Recovery(false, 0, 0, 0, 0), f.service.lastRecovery());
        Path ser = f.service.writeSnapshot();
        removeOrder(id);
        byte[] bytes = Files.readAllBytes(ser);
        bytes[bytes.length / 2] ^= 1;
        Files.write(ser, bytes);
        assertEquals(0, f.service.recoverMissingOrders(), "tampered snapshot");
        assertEquals(0, h.t.number("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void restoredOrdersAreQueuedAndProcessedByTheNormalRecoveryAndNeverGetStockBack() throws Exception {
        h.reset();
        java.util.Map<Long, Integer> initial = PipelineHarness.readStock(h);
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long a = f.submit("QP-A", "BIRYANI", "Chicken Biryani", 1);
        f.service.writeSnapshot();
        removeOrder(a); // the database was lost; the stock table (not part of any snapshot) still shows the deduction
        PipelineHarness p = PipelineHarness.attach(h, PipelineHarness.fast(), initial);
        try {
            RecoveryService recovery = new RecoveryService(h.service, p.orch, h.events, f.service::recoverMissingOrders);
            recovery.recover();
            assertEquals(1, recovery.stats().snapshotInserted());
            long restored = h.t.number("SELECT id FROM orders WHERE external_ref = 'QP-A'");
            p.awaitDelivered(restored);
            assertEquals(0, h.t.number("SELECT stock_deducted FROM orders WHERE id = ?", restored));
            p.assertEachEdgeOnce(restored);
        } finally {
            p.stop();
        }
    }

    private static OrderSnapshotDTO withStation(OrderSnapshotDTO d, long station) {
        return new OrderSnapshotDTO(d.formatVersion(), d.orderId(), d.source(), d.externalRef(), d.trackingCode(), station, d.brandCode(), d.status(),
                d.customerName(), d.deliveryDistanceKm(), d.paymentMode(), d.paymentStatus(), d.platformFee(), d.totalAmount(),
                d.createdAtEpochMillis(), d.estimatedDeliveryAtEpochMillis(), d.stockDeducted(), d.items());
    }

    private static OrderSnapshotDTO withTracking(OrderSnapshotDTO d, String tracking) {
        return new OrderSnapshotDTO(d.formatVersion(), d.orderId(), d.source(), d.externalRef(), tracking, d.stationId(), d.brandCode(), d.status(),
                d.customerName(), d.deliveryDistanceKm(), d.paymentMode(), d.paymentStatus(), d.platformFee(), d.totalAmount(),
                d.createdAtEpochMillis(), d.estimatedDeliveryAtEpochMillis(), d.stockDeducted(), d.items());
    }

    private static OrderSnapshotDTO withPrice(OrderSnapshotDTO d, String price) {
        return new OrderSnapshotDTO(d.formatVersion(), d.orderId(), d.source(), d.externalRef(), d.trackingCode(), d.stationId(), d.brandCode(), d.status(),
                d.customerName(), d.deliveryDistanceKm(), d.paymentMode(), d.paymentStatus(), d.platformFee(), d.totalAmount(),
                d.createdAtEpochMillis(), d.estimatedDeliveryAtEpochMillis(), d.stockDeducted(),
                List.of(new OrderSnapshotDTO.ItemLine(1L, "Chicken Biryani", price, 1)));
    }
}
