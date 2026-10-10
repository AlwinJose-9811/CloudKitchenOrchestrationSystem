package com.cloudkitchen.dao.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.Station;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationDaoTest {

    private DbFixture db;
    private DbTestSupport t;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(new FakeClock());
        t = new DbTestSupport(db);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @Test
    void theThreeSeededBrandsAreActiveAndFoundByCodeAndId() throws Exception {
        List<Station> stations = db.database().withConnection(c -> t.stations.findAllActive(c));
        assertEquals(List.of("BIRYANI", "BURGER", "PIZZA"), stations.stream().map(Station::getCode).toList());
        Station burger = db.database().withConnection(c -> t.stations.findByCode(c, "BURGER")).orElseThrow();
        assertEquals("Burger Barn", burger.getBrandName());
        assertEquals(burger, db.database().withConnection(c -> t.stations.findById(c, burger.getId())).orElseThrow());
        assertTrue(db.database().withConnection(c -> t.stations.findByCode(c, "SUSHI")).isEmpty());
        assertTrue(db.database().withConnection(c -> t.stations.findById(c, 9_999_999L)).isEmpty());
    }

    @Test
    void anInactiveStationIsNotListedButCanStillBeLoaded() throws Exception {
        try {
            t.execute("UPDATE stations SET active = 0 WHERE code = 'PIZZA'");
            assertEquals(2, db.database().withConnection(c -> t.stations.findAllActive(c)).size());
            assertTrue(!db.database().withConnection(c -> t.stations.findByCode(c, "PIZZA")).orElseThrow().isActive());
        } finally {
            t.execute("UPDATE stations SET active = 1 WHERE code = 'PIZZA'");
        }
    }

    @Test
    void codesAreUniqueAndActiveIsAFlag() {
        assertThrows(SQLException.class, () -> t.execute("INSERT INTO stations (code, brand_name, active, created_at) VALUES ('PIZZA', 'Dup', 1, UTC_TIMESTAMP(3))"));
        assertThrows(SQLException.class, () -> t.execute("INSERT INTO stations (code, brand_name, active, created_at) VALUES ('FLAG', 'x', 2, UTC_TIMESTAMP(3))"));
    }
}
