package com.cloudkitchen.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.testsupport.TestData;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Constructor validation and value behaviour of the small domain types. */
class DomainValueObjectsTest {

    private static final Instant T = TestData.NOW;

    // ---- StatusHistoryEntry ----

    @Test
    void historyEntryAcceptsTheInitialRowAndEveryLegalEdge() {
        assertEquals(OrderStatus.PLACED, new StatusHistoryEntry(1, null, OrderStatus.PLACED, T, "system", null).to());
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : from.allowedTargets()) {
                new StatusHistoryEntry(1, from, to, T, "worker:BIRYANI#1", "note");
            }
        }
    }

    @Test
    void historyEntryRejectsIllegalEdgesAndBadFields() {
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(1, OrderStatus.DELIVERED, OrderStatus.PLACED, T, "a", null));
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(1, OrderStatus.PLACED, OrderStatus.PACKED, T, "a", null));
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(1, null, OrderStatus.VALIDATED, T, "a", null), "only PLACED may start a history");
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(0, null, OrderStatus.PLACED, T, "a", null));
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(1, null, OrderStatus.PLACED, T, "", null));
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(1, null, OrderStatus.PLACED, T, "a".repeat(51), null));
        assertThrows(IllegalArgumentException.class, () -> new StatusHistoryEntry(1, null, OrderStatus.PLACED, T, "a", "n".repeat(256)));
        new StatusHistoryEntry(1, null, OrderStatus.PLACED, T, "a".repeat(50), "n".repeat(255));
    }

    // ---- OrderItem ----

    @Test
    void orderItemComputesLineTotalsExactly() {
        OrderItem item = new OrderItem(5, "Raita", new BigDecimal("49.50"), 3);
        assertEquals("148.50", item.lineTotal().toPlainString());
        assertEquals(new OrderItem(5, "Raita", new BigDecimal("49.50"), 3), item, "same item, price and quantity");
        assertNotEquals(new OrderItem(5, "Raita", new BigDecimal("49.50"), 2), item);
    }

    @Test
    void orderItemValidatesItsFields() {
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(0, "x", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "n".repeat(101), BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "x", new BigDecimal("-0.01"), 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "x", new BigDecimal("1.001"), 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "x", BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "x", BigDecimal.ONE, 21));
        assertEquals("0.00", new OrderItem(1, "free", BigDecimal.ZERO, 20).lineTotal().toPlainString());
        assertEquals("100.00", new OrderItem(1, "max", new BigDecimal("5.00"), 20).lineTotal().toPlainString());
    }

    // ---- Station, MenuItem, StockLevel ----

    @Test
    void stationNormalisesItsCodeAndValidates() {
        assertEquals("BIRYANI", new Station(1, " biryani ", "Spice Route Biryani", true).getCode());
        assertThrows(IllegalArgumentException.class, () -> new Station(0, "A", "x", true));
        assertThrows(IllegalArgumentException.class, () -> new Station(1, "", "x", true));
        assertThrows(IllegalArgumentException.class, () -> new Station(1, "BAD CODE", "x", true));
        assertThrows(IllegalArgumentException.class, () -> new Station(1, "A".repeat(21), "x", true));
        assertThrows(IllegalArgumentException.class, () -> new Station(1, "A", "", true));
        assertThrows(IllegalArgumentException.class, () -> new Station(1, "A", "n".repeat(81), true));
        assertEquals(new Station(1, "A", "One", true), new Station(1, "A", "One", true));
        assertNotEquals(new Station(1, "A", "One", true), new Station(2, "A", "One", true));
    }

    @Test
    void menuItemValidatesPriceAndNames() {
        assertThrows(IllegalArgumentException.class, () -> new MenuItem(0, 1, "x", BigDecimal.ONE, true));
        assertThrows(IllegalArgumentException.class, () -> new MenuItem(1, 0, "x", BigDecimal.ONE, true));
        assertThrows(IllegalArgumentException.class, () -> new MenuItem(1, 1, " ", BigDecimal.ONE, true));
        assertThrows(IllegalArgumentException.class, () -> new MenuItem(1, 1, "x", new BigDecimal("-1"), true));
        assertThrows(IllegalArgumentException.class, () -> new MenuItem(1, 1, "x", new BigDecimal("1.234"), true));
        assertEquals("10.00", new MenuItem(1, 1, "x", new BigDecimal("10"), true).getPrice().toPlainString(), "scale is normalised to 2");
    }

    @Test
    void stockLevelIsLowAtOrBelowTheReorderLevel() {
        assertFalse(new StockLevel(1, 11, 10).isLow());
        assertTrue(new StockLevel(1, 10, 10).isLow(), "equal counts as low");
        assertTrue(new StockLevel(1, 0, 10).isLow());
        assertTrue(new StockLevel(1, 0, 0).isLow());
        assertThrows(IllegalArgumentException.class, () -> new StockLevel(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new StockLevel(1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new StockLevel(0, 0, 0));
    }

    // ---- StaffUser ----

    @Test
    void staffUserLowerCasesTheUsernameAndNeverPrintsTheHash() {
        StaffUser user = new StaffUser(7, "  Admin ", "pbkdf2_sha256$600000$c2FsdA==$aGFzaA==", Role.ADMIN, true, 0, null, 0);
        assertEquals("admin", user.getUsername());
        assertFalse(user.toString().contains("pbkdf2"), user.toString());
        assertFalse(user.toString().contains("aGFzaA"), user.toString());
        assertEquals(Role.ADMIN, user.getRole());
    }

    @Test
    void staffUserValidatesItsFields() {
        String hash = "h";
        assertThrows(IllegalArgumentException.class, () -> new StaffUser(0, "a", hash, Role.ADMIN, true, 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new StaffUser(1, " ", hash, Role.ADMIN, true, 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new StaffUser(1, "a", "", Role.ADMIN, true, 0, null, 0));
        assertThrows(NullPointerException.class, () -> new StaffUser(1, "a", hash, null, true, 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new StaffUser(1, "a", hash, Role.ADMIN, true, -1, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new StaffUser(1, "a", hash, Role.ADMIN, true, 0, null, -1));
    }

    // ---- PaymentResult ----

    @Test
    void paymentResultKeepsTheThreeAmountsConsistent() {
        PaymentResult ok = new PaymentResult(PaymentStatus.PAID, new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("80.00"));
        assertEquals("80.00", ok.netToKitchen().toPlainString());
        assertThrows(IllegalArgumentException.class, () -> new PaymentResult(PaymentStatus.PAID, new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("79.99")));
        assertThrows(IllegalArgumentException.class, () -> new PaymentResult(PaymentStatus.PAID, new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("-10.00")));
        assertThrows(IllegalArgumentException.class, () -> new PaymentResult(PaymentStatus.PAID, new BigDecimal("-1.00"), BigDecimal.ZERO, new BigDecimal("-1.00")));
        assertThrows(IllegalArgumentException.class, () -> new PaymentResult(PaymentStatus.PAID, new BigDecimal("1.001"), BigDecimal.ZERO, new BigDecimal("1.001")));
        assertThrows(NullPointerException.class, () -> new PaymentResult(null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE));
    }
}
