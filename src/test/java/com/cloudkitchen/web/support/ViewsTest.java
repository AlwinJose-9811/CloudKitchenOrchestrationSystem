package com.cloudkitchen.web.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.dto.StockRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The conversion of service results into what a JSP can read: maps, lists, UTC text and plain decimals, and nothing else. */
class ViewsTest {

    private record Inner(String label, BigDecimal price) {
    }

    private record Outer(long id, Inner inner, List<Inner> lines, Instant at, OrderStatus status, Long absent) {
    }

    @Test
    void aRecordBecomesAMapOfItsComponentsAndNestedRecordsAndListsFollow() {
        Outer outer = new Outer(7, new Inner("a", new BigDecimal("1.50")), List.of(new Inner("b", new BigDecimal("2.00"))),
                Instant.parse("2026-10-11T16:03:26Z"), OrderStatus.ROUTED, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Views.of(outer);
        assertEquals(List.of("id", "inner", "lines", "at", "status", "absent"), List.copyOf(map.keySet()), "component order is kept");
        assertEquals(7L, map.get("id"));
        assertEquals("1.50", ((Map<?, ?>) map.get("inner")).get("price"));
        assertEquals("2.00", ((Map<?, ?>) ((List<?>) map.get("lines")).get(0)).get("price"));
        assertEquals("2026-10-11 16:03:26 UTC", map.get("at"), "times are UTC text with the label");
        assertSame(OrderStatus.ROUTED, map.get("status"));
        assertTrue(map.containsKey("absent") && map.get("absent") == null);
    }

    @Test
    void theResultIsReadOnlyAtEveryLevel() {
        Object view = Views.of(new Outer(1, new Inner("a", BigDecimal.ONE), List.of(), Instant.EPOCH, OrderStatus.PLACED, 1L));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) view).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) ((Map<?, ?>) view).get("inner")).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) ((Map<?, ?>) view).get("lines")).add(1));
    }

    @Test
    void decimalsArePlainTextAndDoublesHaveOneDecimalPlace() {
        assertEquals("0.00", Views.of(new BigDecimal("0.00")));
        assertEquals("100000000", Views.of(new BigDecimal("1E+8")), "no scientific notation");
        MetricsView metrics = new MetricsView(1, 2, 3, 4, 5, 12.345);
        assertEquals("12.3", ((Map<?, ?>) Views.of(metrics)).get("averageProcessingMillis"));
        assertEquals("0.0", Views.of(0.0d));
    }

    @Test
    void aMapKeepsItsKeysAndOrderAndConvertsItsValues() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        counts.put(OrderStatus.DELIVERED, 3L);
        counts.put(OrderStatus.PLACED, 1L);
        Map<?, ?> view = (Map<?, ?>) Views.of(counts);
        assertEquals(List.of(OrderStatus.PLACED, OrderStatus.DELIVERED), List.copyOf(view.keySet()));
        assertEquals(1L, view.get(OrderStatus.PLACED));
    }

    @Test
    void simpleValuesAndNullAreUnchangedAndAnArrayBecomesAList() {
        assertEquals(null, Views.of(null));
        assertEquals("text", Views.of("text"));
        assertEquals(5, Views.of(5));
        assertEquals(true, Views.of(true));
        assertEquals(List.of(OrderStatus.PLACED, OrderStatus.FAILED), Views.of(new OrderStatus[] {OrderStatus.PLACED, OrderStatus.FAILED}));
        assertEquals("x", ((Map<?, ?>) Views.of(new StockRow(1, "x", "B", 1, 2, true))).get("itemName"));
    }

    @Test
    void anythingElseIsRefusedSoAViewCanNeverHoldAServiceOrADomainObject() {
        assertThrows(IllegalArgumentException.class, () -> Views.of(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Views.of(new StringBuilder("x")));
        assertThrows(IllegalArgumentException.class, () -> Views.of(List.of(new Object())));
        assertThrows(IllegalArgumentException.class, () -> Views.of(Map.of("k", new Thread())));
    }
}
