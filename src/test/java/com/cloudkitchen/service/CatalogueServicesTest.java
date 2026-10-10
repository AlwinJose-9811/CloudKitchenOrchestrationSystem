package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.dto.Page;
import com.cloudkitchen.dto.StockRow;
import com.cloudkitchen.exception.ValidationException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** MenuService, StockService and OrderQueryService against the real database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogueServicesTest {

    private DbFixture db;
    private DbTestSupport t;
    private MenuService menu;
    private StockService stock;
    private OrderQueryService query;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(new FakeClock());
        t = new DbTestSupport(db);
        menu = new MenuService(db.database(), t.menu, t.stock, t.stations);
        stock = new StockService(db.database(), t.stock);
        query = new OrderQueryService(db.database(), t.orders);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
    }

    @Test
    void menuReadsAndTheActiveFilter() throws Exception {
        assertEquals(15, menu.listAll().size());
        assertEquals(5, menu.activeForStation("PIZZA").size());
        assertEquals(List.of(), menu.activeForStation("SUSHI"));
        long brownie = t.itemId("Brownie");
        assertTrue(menu.update(brownie, new BigDecimal("139.50"), false));
        assertEquals(4, menu.activeForStation("PIZZA").size());
        Map<Long, MenuItem> found = menu.findByIds(List.of(brownie));
        assertEquals("139.50", found.get(brownie).getPrice().toPlainString());
        assertFalse(menu.update(9_999_999L, BigDecimal.ONE, true));
    }

    @Test
    void creatingAnItemCreatesItsStockRowInTheSameTransaction() throws Exception {
        long id = menu.create("PIZZA", "  Calzone ", new BigDecimal("349"), 12, 4);
        assertEquals("Calzone", menu.findByIds(List.of(id)).get(id).getName());
        assertEquals("349.00", menu.findByIds(List.of(id)).get(id).getPrice().toPlainString());
        StockRow row = stock.listAll().stream().filter(r -> r.itemId() == id).findFirst().orElseThrow();
        assertEquals(12, row.stockQty());
        assertEquals(4, row.reorderLevel());
        assertEquals("PIZZA", row.brandCode());
    }

    @Test
    void invalidMenuInputIsRefusedWithFieldErrorsAndNothingIsStored() throws Exception {
        assertThrows(ValidationException.class, () -> menu.create("PIZZA", " ", new BigDecimal("10.00"), 1, 1));
        assertThrows(ValidationException.class, () -> menu.create("PIZZA", "x".repeat(101), new BigDecimal("10.00"), 1, 1));
        assertThrows(ValidationException.class, () -> menu.create("PIZZA", "Neg", new BigDecimal("-1"), 1, 1));
        assertThrows(ValidationException.class, () -> menu.create("PIZZA", "TooPrecise", new BigDecimal("1.234"), 1, 1));
        assertThrows(ValidationException.class, () -> menu.create("PIZZA", "TooCostly", new BigDecimal("100000.00"), 1, 1));
        assertThrows(ValidationException.class, () -> menu.create("PIZZA", "NoStock", new BigDecimal("1.00"), -1, 1));
        assertThrows(ValidationException.class, () -> menu.create("SUSHI", "Roll", new BigDecimal("1.00"), 1, 1));
        ValidationException taken = assertThrows(ValidationException.class, () -> menu.create("PIZZA", "Margherita", new BigDecimal("1.00"), 1, 1));
        assertEquals("NAME_TAKEN", taken.getErrors().get(0).code());
        assertThrows(ValidationException.class, () -> menu.update(t.itemId("Cola"), new BigDecimal("-5"), true));
        assertEquals(15, menu.listAll().size());
        assertEquals(15, stock.listAll().size());
    }

    @Test
    void stockAdjustmentIsValidatedAndAbsolute() throws Exception {
        long cola = t.itemId("Cola");
        stock.adjust(cola, 7, 7);
        assertTrue(stock.listLow().stream().anyMatch(r -> r.itemId() == cola), "stock equal to the reorder level is low");
        stock.adjust(cola, 8, 7);
        assertFalse(stock.listLow().stream().anyMatch(r -> r.itemId() == cola));
        assertThrows(ValidationException.class, () -> stock.adjust(cola, -1, 5));
        assertThrows(ValidationException.class, () -> stock.adjust(cola, 5, -1));
        assertThrows(ValidationException.class, () -> stock.adjust(cola, StockService.MAX_QUANTITY + 1, 5));
        ValidationException unknown = assertThrows(ValidationException.class, () -> stock.adjust(9_999_999L, 5, 5));
        assertEquals("ITEM_UNKNOWN", unknown.getErrors().get(0).code());
        assertEquals(8, t.stockOf("Cola"));
    }

    @Test
    void theOrderListPagesNewestFirstAndFilters() throws Exception {
        for (int i = 1; i <= 25; i++) {
            t.persistAt(t.create(t.request(i % 2 == 0 ? OrderSource.ZOMATO : OrderSource.SWIGGY, "Q-" + i, "BURGER", "Cola", 1)), OrderStatus.VALIDATED);
        }
        Page<OrderSummary> first = query.list(OrderFilter.any(), 1);
        Page<OrderSummary> second = query.list(null, 2);
        assertEquals(20, first.items().size());
        assertEquals(5, second.items().size());
        assertEquals(25, first.total());
        assertEquals(2, first.totalPages());
        assertTrue(first.items().get(0).id() > first.items().get(19).id());
        assertEquals(first.page(), query.list(OrderFilter.any(), 0).page(), "page 0 is read as page 1");
        assertEquals(13, query.list(new OrderFilter(null, OrderSource.SWIGGY, null, null, null), 1).total());
        assertEquals(1, query.list(new OrderFilter(null, null, "Q-25", null, null), 1).total());
        assertEquals(25L, query.countByStatus().get(OrderStatus.VALIDATED));
    }

    @Test
    void detailCarriesItemsAndHistoryAndTheTrackingLookupWorks() throws Exception {
        var order = t.biryaniOrder("D-1");
        long id = t.persistAt(order, OrderStatus.VALIDATED, OrderStatus.ROUTED);
        var detail = query.detail(id).orElseThrow();
        assertEquals(2, detail.getItems().size());
        assertEquals(List.of(OrderStatus.PLACED, OrderStatus.VALIDATED, OrderStatus.ROUTED),
                detail.getHistory().stream().map(h -> h.to()).toList());
        assertEquals(id, query.byTrackingCode(order.getTrackingCode()).orElseThrow().getId());
        assertTrue(query.detail(31337L).isEmpty());
        assertTrue(query.byTrackingCode("NOPENOPENOPE").isEmpty());
    }
}
