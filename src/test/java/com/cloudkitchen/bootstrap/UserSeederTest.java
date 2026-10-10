package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.domain.StaffUser;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.exception.ConfigurationException;
import com.cloudkitchen.service.PasswordHasher;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.DbTestSupport;
import com.cloudkitchen.testsupport.FakeClock;
import com.cloudkitchen.testsupport.TestDatabase;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserSeederTest {

    private DbFixture db;
    private DbTestSupport t;
    private PasswordHasher hasher;
    private UserSeeder seeder;
    private String adminPassword;
    private String staffPassword;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(new FakeClock());
        t = new DbTestSupport(db);
        hasher = new PasswordHasher();
        seeder = new UserSeeder(db.database(), t.users, hasher);
    }

    @AfterAll
    void stop() {
        db.close();
    }

    @BeforeEach
    void clean() throws Exception {
        db.resetData();
        adminPassword = "A" + TestDatabase.randomHex(10);
        staffPassword = "S" + TestDatabase.randomHex(10);
    }

    private AppConfig config(String adminName, String adminPw, String staffName, String staffPw) {
        Properties p = new Properties();
        p.setProperty("db.password", db.config().dbPassword());
        p.setProperty("snapshot.hmac.secret", TestDatabase.randomHex(32));
        if (adminName != null) {
            p.setProperty("bootstrap.admin.username", adminName);
        }
        if (adminPw != null) {
            p.setProperty("bootstrap.admin.password", adminPw);
        }
        if (staffName != null) {
            p.setProperty("bootstrap.staff.username", staffName);
        }
        if (staffPw != null) {
            p.setProperty("bootstrap.staff.password", staffPw);
        }
        return AppConfig.fromProperties(p);
    }

    private List<StaffUser> users() throws Exception {
        return db.database().withConnection(c -> t.users.findAll(c));
    }

    @Test
    void anEmptyTableGetsOneAdminAndOneStaffAccountWithVerifiableHashes() throws Exception {
        assertEquals(2, seeder.seedIfEmpty(config("Boss", adminPassword, "cook", staffPassword)));
        List<StaffUser> all = users();
        assertEquals(2, all.size());
        StaffUser admin = all.stream().filter(u -> u.getRole() == Role.ADMIN).findFirst().orElseThrow();
        StaffUser staff = all.stream().filter(u -> u.getRole() == Role.KITCHEN_STAFF).findFirst().orElseThrow();
        assertEquals("boss", admin.getUsername());
        assertEquals("cook", staff.getUsername());
        assertTrue(hasher.verify(adminPassword.toCharArray(), admin.getPasswordHash()));
        assertTrue(hasher.verify(staffPassword.toCharArray(), staff.getPasswordHash()));
        assertFalse(hasher.verify(staffPassword.toCharArray(), admin.getPasswordHash()));
        assertTrue(admin.getPasswordHash().startsWith("pbkdf2_sha256$600000$"));
        assertTrue(admin.isEnabled());
        assertEquals(0, admin.getFailedAttempts());
    }

    @Test
    void thePasswordsAreNeverStoredInPlainTextAnywhere() throws Exception {
        seeder.seedIfEmpty(config("boss", adminPassword, "cook", staffPassword));
        for (String pw : List.of(adminPassword, staffPassword)) {
            assertEquals(0, t.number("SELECT COUNT(*) FROM staff_users WHERE password_hash LIKE ? OR username LIKE ?", "%" + pw + "%", "%" + pw + "%"));
        }
    }

    @Test
    void aSecondStartWithDifferentConfigurationChangesNothing() throws Exception {
        seeder.seedIfEmpty(config("boss", adminPassword, "cook", staffPassword));
        String before = t.text("SELECT password_hash FROM staff_users WHERE username = 'boss'");
        assertEquals(0, seeder.seedIfEmpty(config("someone-else", "B" + TestDatabase.randomHex(10), "another", "C" + TestDatabase.randomHex(10))));
        assertEquals(2, users().size());
        assertEquals(before, t.text("SELECT password_hash FROM staff_users WHERE username = 'boss'"));
        assertEquals(0, t.number("SELECT COUNT(*) FROM staff_users WHERE username IN ('someone-else','another')"));
    }

    @Test
    void missingCredentialsWithAnEmptyTableStopTheStartWithAClearMessageAndCreateNothing() throws Exception {
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> seeder.seedIfEmpty(config("boss", null, "cook", staffPassword)));
        assertTrue(e.getMessage().contains("bootstrap.admin"));
        assertFalse(e.getMessage().contains(staffPassword));
        assertThrows(ConfigurationException.class, () -> seeder.seedIfEmpty(config(null, null, null, null)));
        assertEquals(0, users().size(), "all or nothing: the admin is not created when the staff account cannot be");
    }

    @Test
    void identicalUsernamesAreRefusedBeforeAnythingIsWritten() throws Exception {
        assertThrows(ConfigurationException.class, () -> seeder.seedIfEmpty(config("same", adminPassword, "SAME", staffPassword)));
        assertEquals(0, users().size());
    }

    @Test
    void nothingSecretReachesTheLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(UserSeeder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level old = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addAppender(appender);
        try {
            seeder.seedIfEmpty(config("boss", adminPassword, "cook", staffPassword));
            String hash = t.text("SELECT password_hash FROM staff_users WHERE username = 'boss'");
            assertFalse(appender.list.isEmpty(), "the seeding is logged");
            for (ILoggingEvent event : appender.list) {
                String text = event.getFormattedMessage() + event.getThrowableProxy();
                assertFalse(text.contains(adminPassword) || text.contains(staffPassword) || text.contains(hash), text);
                assertFalse(text.contains("boss"), "not even usernames: " + text);
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(old);
        }
    }
}
