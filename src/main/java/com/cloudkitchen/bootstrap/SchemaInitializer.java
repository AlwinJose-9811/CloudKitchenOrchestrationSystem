package com.cloudkitchen.bootstrap;

import com.cloudkitchen.db.Database;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.util.TimeUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the versioned SQL scripts under {@code /db} (SDD TX9, DATABASE_DESIGN TS-9). Statements run one by one in
 * auto-commit mode; a script is recorded in {@code schema_version} only after all its statements succeeded, so a
 * script that failed halfway is simply run again from its start. That is safe because every statement is idempotent
 * ({@code CREATE TABLE IF NOT EXISTS}, guarded inserts). A failure stops the start-up with a message naming the
 * script and statement number; the application never continues with a half-built schema.
 */
public final class SchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    /** The scripts in application order. */
    public static final List<String> SCRIPTS = List.of("V001__schema.sql", "V002__seed_reference_data.sql");

    private static final String CREATE_VERSION_TABLE = "CREATE TABLE IF NOT EXISTS schema_version ("
            + " version INT NOT NULL, script_name VARCHAR(100) NOT NULL, applied_at DATETIME(3) NOT NULL,"
            + " PRIMARY KEY (version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private final Database database;
    private final Clock clock;

    public SchemaInitializer(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Applies every script not yet recorded and returns the versions applied by this call (empty on a re-run). */
    public List<Integer> apply() throws CloudKitchenException {
        return database.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_VERSION_TABLE);
            }
            Set<Integer> applied = appliedVersions(connection);
            List<Integer> newlyApplied = new ArrayList<>();
            for (String script : SCRIPTS) {
                int version = versionOf(script);
                if (applied.contains(version)) {
                    continue;
                }
                runScript(connection, script);
                record(connection, version, script);
                newlyApplied.add(version);
                LOG.info("Applied database script {}", script);
            }
            return newlyApplied;
        });
    }

    private static Set<Integer> appliedVersions(Connection connection) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT version FROM schema_version");
             ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
        }
        return versions;
    }

    private void runScript(Connection connection, String script) throws SQLException, PersistenceException {
        List<String> statements = readStatements(script);
        int number = 0;
        for (String sql : statements) {
            number++;
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new PersistenceException("Database script " + script + " failed at statement " + number + " of "
                        + statements.size() + " (SQLState " + e.getSQLState() + ", code " + e.getErrorCode()
                        + "); the schema was not completed", e);
            }
        }
    }

    private void record(Connection connection, int version, String script) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO schema_version (version, script_name, applied_at) VALUES (?, ?, ?)")) {
            insert.setInt(1, version);
            insert.setString(2, script);
            insert.setObject(3, TimeUtil.toUtcLocal(clock.instant()));
            insert.executeUpdate();
        }
    }

    static int versionOf(String script) {
        Matcher m = VERSION.matcher(script);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a versioned script name: " + script);
        }
        return Integer.parseInt(m.group(1));
    }

    /** Splits a script into statements: {@code --} comment lines are dropped, a statement ends at a line-final semicolon. */
    static List<String> readStatements(String script) throws PersistenceException {
        String text;
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/db/" + script)) {
            if (in == null) {
                throw new PersistenceException("Database script not found on the class path: /db/" + script, null);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read database script " + script, e);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().strip();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            throw new PersistenceException("Database script " + script + " ends in an unterminated statement", null);
        }
        return statements;
    }
}
