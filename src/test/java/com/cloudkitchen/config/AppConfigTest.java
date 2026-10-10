package com.cloudkitchen.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 1 subset of AppConfigTest: defaults, required key, placeholder rejection and secret masking. */
class AppConfigTest {

    private static Properties valid() {
        Properties p = new Properties();
        p.setProperty("db.password", "generated-test-password-1");
        return p;
    }

    @Test
    void defaultsApplyWhenOnlyTheRequiredKeyIsPresent() {
        AppConfig config = AppConfig.fromProperties(valid());
        assertEquals(8080, config.serverPort());
        assertEquals("embedded", config.dbMode());
        assertEquals(3307, config.dbEmbeddedPort());
        assertEquals(Path.of("./data/db"), config.dbEmbeddedDataDir());
        assertFalse(config.sessionCookieSecure());
    }

    @Test
    void portZeroMeansRandomAndIsAccepted() {
        Properties p = valid();
        p.setProperty("server.port", "0");
        assertEquals(0, AppConfig.fromProperties(p).serverPort());
    }

    @Test
    void missingPasswordIsRejected() {
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(new Properties()));
    }

    @Test
    void placeholderSecretsAreRejected() {
        Properties p = valid();
        p.setProperty("db.password", AppConfig.PLACEHOLDER_PREFIX + "_db_password");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(p));
        assertTrue(e.getMessage().contains("db.password"));
        assertFalse(e.getMessage().contains(AppConfig.PLACEHOLDER_PREFIX + "_db_password"));
    }

    @Test
    void invalidNumbersAndModesAreRejected() {
        Properties badPort = valid();
        badPort.setProperty("server.port", "70000");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(badPort));
        Properties notNumber = valid();
        notNumber.setProperty("server.port", "eighty");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(notNumber));
        Properties badMode = valid();
        badMode.setProperty("db.mode", "h2");
        assertThrows(ConfigurationException.class, () -> AppConfig.fromProperties(badMode));
    }

    @Test
    void toStringMasksSecrets() {
        String text = AppConfig.fromProperties(valid()).toString();
        assertFalse(text.contains("generated-test-password-1"));
        assertTrue(text.contains("db.password=[MASKED]"));
    }

    @Test
    void missingFileGivesAHelpfulError(@TempDir Path dir) {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> AppConfig.load(dir.resolve("nope.properties")));
        assertTrue(e.getMessage().contains("application.properties.example"));
    }

    @Test
    void loadsAFileAsUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.properties");
        Files.writeString(file, "db.password=generated-test-password-1\nserver.port=0\n");
        assertEquals(0, AppConfig.load(file).serverPort());
    }

    @Test
    void theExampleFileIsUsableOnceItsPlaceholdersAreReplaced(@TempDir Path dir) throws IOException {
        String edited = Files.readString(Path.of("config", "application.properties.example"))
                .replaceAll("CHANGE_ME[A-Za-z0-9_]*", "generated-test-secret-value-0123456789");
        Path file = dir.resolve("application.properties");
        Files.writeString(file, edited);
        AppConfig config = AppConfig.load(file);
        assertEquals(8080, config.serverPort(), "inline comments must not leak into values");
        assertEquals("embedded", config.dbMode());
        assertEquals("cloudkitchen", config.dbName());
        assertEquals(3307, config.dbEmbeddedPort());
    }

    @Test
    void theCommittedExampleFileIsRejectedUntilEdited() {
        Path example = Path.of("config", "application.properties.example");
        assertTrue(Files.isRegularFile(example), "the example file must be committed");
        assertThrows(ConfigurationException.class, () -> AppConfig.load(example));
    }
}
