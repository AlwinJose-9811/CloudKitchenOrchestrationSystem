package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.testsupport.TestData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuCatalogTest {

    @Test
    void looksItemsAndStationsUp() {
        MenuCatalog catalog = TestData.catalog();
        assertEquals(TestData.CHICKEN_BIRYANI, catalog.item(101).orElseThrow());
        assertEquals(TestData.BIRYANI, catalog.station("BIRYANI").orElseThrow());
        assertEquals(TestData.BURGER, catalog.stationById(2).orElseThrow());
        assertTrue(catalog.item(999).isEmpty());
        assertTrue(catalog.station("SUSHI").isEmpty());
        assertTrue(catalog.station(null).isEmpty());
        assertTrue(catalog.stationById(99).isEmpty());
    }

    @Test
    void inactiveStationsAreStillFoundSoCallersCanTellInactiveFromUnknown() {
        assertTrue(TestData.catalog().station("DESSERT").isPresent());
        assertEquals(false, TestData.catalog().station("DESSERT").orElseThrow().isActive());
    }

    @Test
    void duplicatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MenuCatalog.of(List.of(TestData.PIZZA, new Station(9, "PIZZA", "Dup", true)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> MenuCatalog.of(List.of(TestData.PIZZA, new Station(3, "OTHER", "Dup", true)), List.of()));
        MenuItem same = new MenuItem(1, 1, "x", BigDecimal.ONE, true);
        assertThrows(IllegalArgumentException.class, () -> MenuCatalog.of(List.of(), List.of(same, new MenuItem(1, 1, "y", BigDecimal.TEN, true))));
    }

    @Test
    void theCatalogueDoesNotChangeWhenTheSourceListsDo() {
        java.util.List<MenuItem> items = new java.util.ArrayList<>(List.of(TestData.CHICKEN_BIRYANI));
        MenuCatalog catalog = MenuCatalog.of(List.of(TestData.BIRYANI), items);
        items.clear();
        assertTrue(catalog.item(101).isPresent());
    }
}
