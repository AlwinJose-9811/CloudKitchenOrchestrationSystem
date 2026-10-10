package com.cloudkitchen.config;

import com.cloudkitchen.exception.ConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed view of {@code config/application.properties} (SDD section 15). Phase 1 implements only the keys the
 * skeleton needs; other keys in the file are ignored until the phase that owns them adds a getter.
 *
 * <p>Values of secret keys are never printed: {@link #toString()} masks them. A secret that still carries the
 * placeholder from the committed example file is rejected, so the application refuses to start with it.
 */
public final class AppConfig {

    /** Prefix of every placeholder value in {@code application.properties.example}. */
    public static final String PLACEHOLDER_PREFIX = "CHANGE_ME";
    /** System property that overrides the configuration file location. */
    public static final String CONFIG_PROPERTY = "ck.config";
    public static final Path DEFAULT_PATH = Path.of("config", "application.properties");

    private static final Set<String> SECRET_KEYS = Set.of("db.password", "snapshot.hmac.secret",
            "bootstrap.admin.password", "bootstrap.staff.password");

    private final Properties values;

    private AppConfig(Properties values) {
        this.values = values;
    }

    /** Loads the file named by {@code -Dck.config}, or {@code config/application.properties}. */
    public static AppConfig load() {
        String override = System.getProperty(CONFIG_PROPERTY);
        return load(override == null || override.isBlank() ? DEFAULT_PATH : Path.of(override));
    }

    public static AppConfig load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration file not found: " + path
                    + ". Copy config/application.properties.example to config/application.properties and edit it.");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read configuration file " + path, e);
        }
        return fromProperties(properties);
    }

    /** Used by tests, which generate their own values and never read or write the real file. */
    public static AppConfig fromProperties(Properties source) {
        Properties copy = new Properties();
        for (String key : source.stringPropertyNames()) {
            copy.setProperty(key, source.getProperty(key).trim());
        }
        AppConfig config = new AppConfig(copy);
        config.validate();
        return config;
    }

    private void validate() {
        for (String key : new TreeSet<>(SECRET_KEYS)) {
            String value = values.getProperty(key);
            if (value != null && value.startsWith(PLACEHOLDER_PREFIX)) {
                throw new ConfigurationException("Configuration key '" + key
                        + "' still has the placeholder value from the example file; set a real value in the git-ignored file");
            }
        }
        String password = values.getProperty("db.password");
        if (password == null || password.isEmpty()) {
            throw new ConfigurationException("Required configuration key 'db.password' is missing");
        }
        serverPort();
        dbEmbeddedPort();
        String mode = dbMode();
        if (!mode.equals("embedded") && !mode.equals("external")) {
            throw new ConfigurationException("db.mode must be 'embedded' or 'external', was '" + mode + "'");
        }
    }

    /** {@code server.port}, default 8080; 0 selects a random port (tests). */
    public int serverPort() {
        return intValue("server.port", 8080, 0, 65535);
    }

    public boolean sessionCookieSecure() {
        return Boolean.parseBoolean(values.getProperty("server.session.cookie.secure", "false"));
    }

    public String dbMode() {
        return values.getProperty("db.mode", "embedded");
    }

    public String dbName() {
        return values.getProperty("db.name", "cloudkitchen");
    }

    public String dbUser() {
        return values.getProperty("db.user", "ckapp");
    }

    /** Secret. Callers must not log it. */
    public String dbPassword() {
        return values.getProperty("db.password");
    }

    public int dbEmbeddedPort() {
        return intValue("db.embedded.port", 3307, 0, 65535);
    }

    public Path dbEmbeddedDataDir() {
        return Path.of(values.getProperty("db.embedded.data.dir", "./data/db"));
    }

    public Path dbEmbeddedBaseDir() {
        return Path.of(values.getProperty("db.embedded.base.dir", "./data/runtime"));
    }

    private int intValue(String key, int defaultValue, int min, int max) {
        String raw = values.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < min || parsed > max) {
                throw new ConfigurationException(key + " must be between " + min + " and " + max + ", was " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer", e);
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("AppConfig[");
        boolean first = true;
        for (String key : new TreeSet<>(values.stringPropertyNames())) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(key).append('=').append(SECRET_KEYS.contains(key) ? "[MASKED]" : values.getProperty(key));
        }
        return text.append(']').toString();
    }
}
