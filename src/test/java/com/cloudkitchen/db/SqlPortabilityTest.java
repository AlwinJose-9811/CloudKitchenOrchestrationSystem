package com.cloudkitchen.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.SchemaInitializer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** FR-ENV-04: the scripts and the DAO statements stay in the subset that MySQL 8 and MariaDB share. */
class SqlPortabilityTest {

    private static final List<String> FORBIDDEN = List.of("(?i)\\bENUM\\s*\\(", "(?i)\\bJSON\\b", "(?i)\\bCREATE\\s+TRIGGER\\b",
            "(?i)\\bNOW\\s*\\(", "(?i)\\bRETURNING\\b", "(?i)SKIP\\s+LOCKED", "(?i)utf8mb4_0900", "(?i)\\bCREATE\\s+(OR\\s+REPLACE|PROCEDURE|FUNCTION|EVENT)\\b",
            "(?i)\\bSEQUENCE\\b", "(?i)\\bGENERATED\\s+ALWAYS\\b", "(?i)\\bWITH\\s+RECURSIVE\\b", "(?i)ON\\s+CONFLICT", "(?i)\\bMERGE\\b",
            "(?i)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS", "(?i)\\bADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS", "(?i)\\bDELIMITER\\b");

    private static String script(String name) throws IOException {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/db/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theScriptsContainNoForbiddenConstruct() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String name : SchemaInitializer.SCRIPTS) {
            String sql = script(name).replaceAll("(?m)^\\s*--.*$", "");
            for (String regex : FORBIDDEN) {
                if (Pattern.compile(regex).matcher(sql).find()) {
                    offenders.add(name + " matches " + regex);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void everyTableStatesEngineAndCollationExplicitly() throws IOException {
        String sql = script("V001__schema.sql").replaceAll("(?m)^\\s*--.*$", "");
        long tables = Pattern.compile("(?i)CREATE TABLE IF NOT EXISTS").matcher(sql).results().count();
        long engines = Pattern.compile("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci").matcher(sql).results().count();
        assertEquals(8, tables);
        assertEquals(tables, engines, "Phase 1 finding: without a COLLATE the default is utf8mb4_general_ci");
    }

    @Test
    void everyCreateTableIsIdempotentAndNoIndexIsCreatedOutsideATable() throws IOException {
        String sql = script("V001__schema.sql").replaceAll("(?m)^\\s*--.*$", "");
        assertEquals(0, Pattern.compile("(?i)CREATE TABLE (?!IF NOT EXISTS)").matcher(sql).results().count());
        assertEquals(0, Pattern.compile("(?i)CREATE\\s+(UNIQUE\\s+)?INDEX").matcher(sql).results().count(),
                "CREATE INDEX has no portable IF NOT EXISTS, so indexes live inside CREATE TABLE");
    }

    @Test
    void theSeedScriptContainsNoCredentialsAndOnlyGuardedInserts() throws IOException {
        String sql = script("V002__seed_reference_data.sql").replaceAll("(?m)^\\s*--.*$", "");
        assertTrue(!sql.toLowerCase().contains("staff_users") && !sql.toLowerCase().contains("pbkdf2") && !sql.toLowerCase().contains("password"));
        for (String statement : sql.split(";")) {
            String s = statement.strip();
            if (!s.isEmpty()) {
                assertTrue(s.startsWith("INSERT") && s.contains("NOT EXISTS"), "unguarded statement: " + s);
            }
        }
    }

    @Test
    void theDaoStatementsStayInTheSameSubset() throws IOException {
        // FR-ENV-04 covers "all SQL scripts and DAO statements": the same forbidden constructs, searched in the JDBC DAOs.
        java.nio.file.Path dir = java.nio.file.Path.of("src", "main", "java", "com", "cloudkitchen", "dao", "jdbc");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String source = java.nio.file.Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
                // only string literals can hold SQL; Java identifiers such as stamp.now() are not statements
                StringBuilder literals = new StringBuilder();
                java.util.regex.Matcher literal = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").matcher(source);
                while (literal.find()) {
                    literals.append(literal.group(1)).append('\n');
                }
                String code = literals.toString();
                for (String regex : FORBIDDEN) {
                    if (Pattern.compile(regex).matcher(code).find()) {
                        offenders.add(file.getFileName() + " matches " + regex);
                    }
                }
                if (Pattern.compile("(?i)ON\\s+DUPLICATE\\s+KEY|\\bREPLACE\\s+INTO\\b|\\bINSERT\\s+IGNORE\\b|LIMIT\\s+\\?\\s*,").matcher(code).find()) {
                    offenders.add(file.getFileName() + " uses a MySQL-only statement form");
                }
            }
        }
        assertTrue(scanned >= 6, "the six JDBC DAOs were scanned");
        assertEquals(List.of(), offenders);
    }
}
