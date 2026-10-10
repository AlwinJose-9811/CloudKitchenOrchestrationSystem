package com.cloudkitchen.dao.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.exception.PersistenceException;
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuItemDaoTest {

    private DbFixture db;
    private DbTestSupport t;
    private long pizza;

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
        pizza = t.station("PIZZA").getId();
    }

    @Test
    void theSeededCataloguePerStation() throws Exception {
        List<MenuItem> items = db.database().withConnection(c -> t.menu.findByStation(c, pizza, false));
        assertEquals(5, items.size());
        assertEquals(List.of("Margherita", "Farmhouse", "Pepperoni", "Garlic Bread", "Brownie"), items.stream().map(MenuItem::getName).toList());
        assertEquals(15, db.database().withConnection(c -> t.menu.findAll(c)).size());
    }

    @Test
    void activeOnlyHidesDeactivatedItemsAndUpdateChangesPriceAndFlag() throws Exception {
        long margherita = t.itemId("Margherita");
        assertTrue(t.ok(c -> t.menu.update(c, margherita, new BigDecimal("319.50"), false)));
        List<MenuItem> active = db.database().withConnection(c -> t.menu.findByStation(c, pizza, true));
        assertEquals(4, active.size());
        assertFalse(active.stream().anyMatch(i -> i.getId() == margherita));
        MenuItem changed = db.database().withConnection(c -> t.menu.findByIds(c, List.of(margherita))).get(margherita);
        assertEquals("319.50", changed.getPrice().toPlainString());
        assertFalse(changed.isActive());
        assertFalse(t.ok(c -> t.menu.update(c, 9_999_999L, BigDecimal.ONE, true)), "unknown id");
    }

    @Test
    void findByIdsReturnsOnlyTheExistingOnesAndAcceptsEmpty() throws Exception {
        long a = t.itemId("Cola");
        long b = t.itemId("Fries");
        Map<Long, MenuItem> found = db.database().withConnection(c -> t.menu.findByIds(c, List.of(b, a, 9_999_999L, a)));
        assertEquals(2, found.size());
        assertEquals("Cola", found.get(a).getName());
        assertTrue(db.database().withConnection(c -> t.menu.findByIds(c, List.of())).isEmpty());
    }

    @Test
    void insertGivesAGeneratedIdAndKeepsUnicodeNamesAndExactPrices() throws Exception {
        long id = db.database().inTransaction(c -> t.menu.insert(c, pizza, "पनीर पिज़्ज़ा 🍕", new BigDecimal("19.99"), true));
        MenuItem item = db.database().withConnection(c -> t.menu.findByIds(c, List.of(id))).get(id);
        assertEquals("पनीर पिज़्ज़ा 🍕", item.getName());
        assertEquals(new BigDecimal("19.99"), item.getPrice());
        assertEquals(pizza, item.getStationId());
        assertTrue(item.isActive());
    }

    @Test
    void aNameIsUniquePerStationButNotAcrossStations() throws Exception {
        db.database().inTransaction(c -> t.menu.insert(c, pizza, "Limited", new BigDecimal("10.00"), true));
        PersistenceException duplicate = assertThrows(PersistenceException.class,
                () -> db.database().inTransaction(c -> t.menu.insert(c, pizza, "Limited", new BigDecimal("11.00"), true)));
        assertTrue(SqlErrors.isDuplicateKey(duplicate));
        db.database().inTransaction(c -> t.menu.insert(c, t.station("BURGER").getId(), "Limited", new BigDecimal("10.00"), true));
    }

    @Test
    void foreignKeyAndPriceCheckAreEnforced() {
        assertThrows(PersistenceException.class, () -> db.database().inTransaction(c -> t.menu.insert(c, 9_999_999L, "Ghost", BigDecimal.ONE, true)));
        assertThrows(PersistenceException.class, () -> db.database().inTransaction(c -> t.menu.insert(c, pizza, "Free?", new BigDecimal("-0.01"), true)));
    }
}
