package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dao.jdbc.JdbcStaffUserDao;
import com.cloudkitchen.db.DataSourceFactory;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.service.PasswordHasher;
import com.cloudkitchen.testsupport.FakeClock;
import com.cloudkitchen.testsupport.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The real start-up sequence of the application, run three times against one embedded data directory with the
 * database process stopped and started in between: the schema is built once, the reference data seeded once, the two
 * accounts created once, and everything survives the restarts (FR-AUT-07, TX9).
 */
class BootstrapRestartTest {

    private static long count(Database database, String table) throws Exception {
        Long value = database.withConnection(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM " + table); ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
        return value;
    }

    private record Run(List<Integer> applied, int created, long stations, long items, long stock, long users, long versions, String adminHash) {
    }

    private static Run startUp(DbBootstrap.Settings settings, AppConfig config) throws Exception {
        try (DbBootstrap server = DbBootstrap.start(settings);
             HikariDataSource pool = DataSourceFactory.create(config, DataSourceFactory.embeddedUrl(server.host(), server.port(), server.database()))) {
            FakeClock clock = new FakeClock();
            Database database = new Database(pool, clock, 3, 5);
            List<Integer> applied = new SchemaInitializer(database, clock).apply();
            int created = new UserSeeder(database, new JdbcStaffUserDao(clock), new PasswordHasher()).seedIfEmpty(config);
            String hash = database.withConnection(c -> {
                try (PreparedStatement p = c.prepareStatement("SELECT password_hash FROM staff_users WHERE user_role = 'ADMIN'"); ResultSet rs = p.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            });
            return new Run(applied, created, count(database, "stations"), count(database, "menu_items"), count(database, "inventory"),
                    count(database, "staff_users"), count(database, "schema_version"), hash);
        }
    }

    @Test
    void repeatedApplicationStartsAcrossDatabaseRestartsSeedExactlyOnce() throws Exception {
        DbBootstrap.Settings settings = TestDatabase.newSettings("restart");
        try {
            Properties props = new Properties();
            props.setProperty("db.user", settings.appUser());
            props.setProperty("db.name", settings.database());
            props.setProperty("db.password", settings.appPassword());
            props.setProperty("snapshot.hmac.secret", TestDatabase.randomHex(32));
            props.setProperty("bootstrap.admin.username", "boss");
            props.setProperty("bootstrap.admin.password", "A" + TestDatabase.randomHex(10));
            props.setProperty("bootstrap.staff.username", "cook");
            props.setProperty("bootstrap.staff.password", "S" + TestDatabase.randomHex(10));
            AppConfig config = AppConfig.fromProperties(props);

            Run first = startUp(settings, config);
            assertEquals(List.of(1, 2), first.applied(), "first start: both scripts");
            assertEquals(2, first.created());
            assertEquals(3, first.stations());
            assertEquals(15, first.items());
            assertEquals(15, first.stock());
            assertEquals(2, first.users());
            assertEquals(2, first.versions());
            assertTrue(TestDatabase.serverProcessesForPort(settings.port()).isEmpty(), "the database was stopped between starts");

            Run second = startUp(settings, config);
            Run third = startUp(settings, config);
            for (Run later : List.of(second, third)) {
                assertEquals(List.of(), later.applied(), "nothing is applied again");
                assertEquals(0, later.created(), "no second seeding");
                assertEquals(first.stations(), later.stations());
                assertEquals(first.items(), later.items());
                assertEquals(first.stock(), later.stock());
                assertEquals(first.users(), later.users());
                assertEquals(first.versions(), later.versions());
                assertEquals(first.adminHash(), later.adminHash(), "the stored password hash is untouched");
            }
        } finally {
            assertTrue(TestDatabase.serverProcessesForPort(settings.port()).isEmpty(), "no database process left behind");
            TestDatabase.deleteTree(TestDatabase.rootOf(settings));
        }
    }
}
