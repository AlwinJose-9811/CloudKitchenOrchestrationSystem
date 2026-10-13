package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.Station;
import com.cloudkitchen.testsupport.TestData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutingServiceTest {

    private final RoutingService routing = new RoutingService(List.of(TestData.PIZZA, TestData.BIRYANI, TestData.BURGER, TestData.DESSERT));

    @Test
    void eachBrandRoutesToItsOwnStation() {
        assertEquals(TestData.BIRYANI, routing.stationFor("BIRYANI").orElseThrow());
        assertEquals(TestData.BURGER, routing.stationFor("BURGER").orElseThrow());
        assertEquals(TestData.PIZZA, routing.stationFor("PIZZA").orElseThrow());
    }

    @Test
    void anUnknownBrandHasNoStation() {
        assertEquals(Optional.empty(), routing.stationFor("SUSHI"));
        assertEquals(Optional.empty(), routing.stationFor(""));
        assertEquals(Optional.empty(), routing.stationFor(null));
        assertEquals(Optional.empty(), routing.stationFor("biryani"), "codes are exact, upper-case");
    }

    @Test
    void anInactiveStationIsNotRoutable() {
        assertEquals(Optional.empty(), routing.stationFor("DESSERT"));
    }

    @Test
    void stationsAreListedByIdWithoutInactiveOnes() {
        assertEquals(List.of(TestData.BIRYANI, TestData.BURGER, TestData.PIZZA), routing.stations());
    }

    @Test
    void refreshReplacesTheWholeTable() {
        routing.refresh(List.of(TestData.BURGER));
        assertEquals(List.of(TestData.BURGER), routing.stations());
        assertTrue(routing.stationFor("PIZZA").isEmpty());
        routing.refresh(List.of(new Station(3, "PIZZA", "Pizza Piazza", false)));
        assertTrue(routing.stations().isEmpty());
    }

    @Test
    void aListTakenBeforeRefreshIsNotAffected() {
        List<Station> before = routing.stations();
        routing.refresh(List.of());
        assertEquals(3, before.size());
        assertTrue(routing.stations().isEmpty());
    }

    @Test
    void duplicateActiveCodesAreRejectedAndTheOldTableSurvives() {
        assertThrows(IllegalArgumentException.class,
                () -> routing.refresh(List.of(TestData.PIZZA, new Station(9, "PIZZA", "Other", true))));
        assertEquals(3, routing.stations().size());
    }

    @Test
    void emptyServiceRoutesNothing() {
        assertTrue(new RoutingService().stations().isEmpty());
        assertThrows(NullPointerException.class, () -> new RoutingService().refresh(null));
    }
}
