package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.FakeClock;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaInitializerTest {

    private FakeClock clock;
    private DbFixture db;

    @BeforeAll
    void start() throws Exception {
        clock = new FakeClock();
        db = DbFixture.create(clock);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws SQLException {
        db.resetData();
    }

    private long count(String sql) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(sql); ResultSet rs = p.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void bothScriptsAreRecordedOnceInOrder() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT version, script_name FROM schema_version ORDER BY version")) {
            while (rs.next()) {
                rows.add(rs.getInt(1) + ":" + rs.getString(2));
            }
        }
        assertEquals(List.of("1:V001__schema.sql", "2:V002__seed_reference_data.sql"), rows);
    }

    @Test
    void aSecondRunAppliesNothingAndChangesNoData() throws Exception {
        long stations = count("SELECT COUNT(*) FROM stations");
        long items = count("SELECT COUNT(*) FROM menu_items");
        long stock = count("SELECT COUNT(*) FROM inventory");
        assertEquals(List.of(), new SchemaInitializer(db.database(), clock).apply());
        assertEquals(stations, count("SELECT COUNT(*) FROM stations"));
        assertEquals(items, count("SELECT COUNT(*) FROM menu_items"));
        assertEquals(stock, count("SELECT COUNT(*) FROM inventory"));
        assertEquals(2, count("SELECT COUNT(*) FROM schema_version"));
    }

    @Test
    void referenceDataIsSeededExactlyAsDesigned() throws SQLException {
        assertEquals(3, count("SELECT COUNT(*) FROM stations WHERE code IN ('BIRYANI','BURGER','PIZZA') AND active = 1"));
        assertEquals(15, count("SELECT COUNT(*) FROM menu_items"));
        assertEquals(5, count("SELECT COUNT(*) FROM menu_items m JOIN stations s ON s.id = m.station_id WHERE s.code = 'BURGER'"));
        assertEquals(15, count("SELECT COUNT(*) FROM inventory WHERE stock_qty = 50 AND reorder_level = 10"));
        assertEquals(0, count("SELECT COUNT(*) FROM staff_users"), "users come from UserSeeder, never from SQL");
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(
                "SELECT price FROM menu_items WHERE name = 'Chicken Biryani'"); ResultSet rs = p.executeQuery()) {
            rs.next();
            assertEquals(new BigDecimal("249.00"), rs.getBigDecimal(1));
        }
    }

    @Test
    void rerunningAfterDeletingTheVersionRowAddsNoDuplicateReferenceData() throws Exception {
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM schema_version WHERE version = 2");
        }
        assertEquals(List.of(2), new SchemaInitializer(db.database(), clock).apply());
        assertEquals(3, count("SELECT COUNT(*) FROM stations"));
        assertEquals(15, count("SELECT COUNT(*) FROM menu_items"));
        assertEquals(15, count("SELECT COUNT(*) FROM inventory"));
    }

    @Test
    void aScriptThatFailedHalfwayIsRerunFromItsStart() throws Exception {
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM schema_version WHERE version = 1");
        }
        assertEquals(List.of(1), new SchemaInitializer(db.database(), clock).apply(), "V001 is idempotent");
        assertEquals(2, count("SELECT COUNT(*) FROM schema_version"));
    }

    @Test
    void theTablesAndConstraintsOfTheDesignExist() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection c = db.connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1).toLowerCase());
            }
        }
        java.util.Collections.sort(tables);
        assertEquals(List.of("inventory", "menu_items", "order_items", "order_status_history", "orders", "schema_version",
                "staff_users", "stations"), tables);
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orders' AND index_name = 'uq_orders_source_ref' AND seq_in_index = 1 AND column_name = 'source'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orders' AND index_name = 'uq_orders_source_ref' AND seq_in_index = 2 AND column_name = 'external_ref'"));
        assertEquals(3, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'staff_users' AND column_name IN ('failed_attempts','locked_until','session_version')"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'inventory' AND column_name = 'reorder_level'"));
        assertEquals(7, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name IN ('claim_epoch','claimed_by','lease_expires_at','compensation_state','compensation_reason','compensation_attempts','compensation_next_at')"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'order_status_history' AND index_name = 'uq_history_order_edge' AND seq_in_index = 1"));
        for (String index : List.of("idx_orders_status_updated", "idx_orders_compensation", "idx_orders_claimed_by")) {
            assertEquals(1, count("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND index_name = '" + index + "'"), index);
        }
    }

    @Test
    void referenceDataSurvivesUnicodeNamesAndExactDecimals() throws SQLException {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO menu_items (station_id, name, price, active, created_at, updated_at) SELECT id, ?, ?, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM stations WHERE code = 'PIZZA'")) {
            p.setString(1, "पनीर पिज़्ज़ा 🍕");
            p.setBigDecimal(2, new BigDecimal("19.99"));
            p.executeUpdate();
        }
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(
                "SELECT price FROM menu_items WHERE name = ?")) {
            p.setString(1, "पनीर पिज़्ज़ा 🍕");
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("19.99"), rs.getBigDecimal(1));
            }
        }
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM menu_items WHERE name = '" + "पनीर पिज़्ज़ा 🍕" + "'");
        }
    }

    @Test
    void anUnknownScriptNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaInitializer.versionOf("schema.sql"));
        assertEquals(1, SchemaInitializer.versionOf("V001__schema.sql"));
    }

    @Test
    void aMissingScriptResourceIsReportedClearly() {
        PersistenceException e = assertThrows(PersistenceException.class, () -> SchemaInitializer.readStatements("V999__nope.sql"));
        assertTrue(e.getMessage().contains("V999__nope.sql"));
    }
}
