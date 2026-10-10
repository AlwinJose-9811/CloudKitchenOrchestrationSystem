package com.cloudkitchen.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Source-scan architecture rules (Phase 1 subset; later phases add the rules listed in TESTING_STRATEGY).
 * Includes the guard for deviation DEV-1: Spring may only appear as a transitive dependency of MariaDB4j.
 */
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path TEST = Path.of("src", "test", "java");
    private static final Path WEBAPP = Path.of("src", "main", "webapp");
    private static final String ROOT = "com.cloudkitchen";

    private static final List<String> SDD_PACKAGES = List.of("bootstrap", "config", "domain", "domain.enums",
            "domain.contract", "exception", "dto", "dao", "dao.jdbc", "db", "service", "pipeline", "io", "snapshot",
            "util", "web.controller", "web.filter", "web.listener", "web.support");

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Package of a source file below the given root, relative to com.cloudkitchen ("" for the root package). */
    private static String relativePackage(Path root, Path file) {
        Path parent = root.resolve("com").resolve("cloudkitchen").relativize(file.getParent());
        return parent.toString().replace(file.getFileSystem().getSeparator(), ".");
    }

    private static boolean imports(String source, String prefix) {
        return Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?" + Pattern.quote(prefix)).matcher(source).find();
    }

    @Test
    void noSpringTypesAreUsedInOurOwnCode() {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(MAIN, TEST)) {
            for (Path file : javaFiles(root)) {
                if (read(file).contains("org.springframework") && !file.endsWith("ArchitectureRulesTest.java")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "DEV-1 allows Spring only as a transitive dependency of MariaDB4j");
    }

    @Test
    void thePomDeclaresNoForbiddenLibraryDirectly() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        Set<String> forbiddenGroups = Set.of("org.springframework", "org.springframework.boot", "org.hibernate",
                "org.hibernate.orm", "jakarta.persistence", "javax.persistence", "org.mockito", "com.h2database",
                "org.eclipse.persistence");
        NodeList dependencies = pom.getElementsByTagName("dependency");
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            String group = dependency.getElementsByTagName("groupId").item(0).getTextContent().trim();
            if (forbiddenGroups.contains(group)) {
                offenders.add(group + ":" + dependency.getElementsByTagName("artifactId").item(0).getTextContent().trim());
            }
        }
        assertEquals(List.of(), offenders, "frozen stack: no Spring, JPA, Hibernate, Mockito or H2 (A-14)");
    }

    @Test
    void servletTypesAreOnlyUsedInTheWebPackages() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String pkg = relativePackage(MAIN, file);
            if (!pkg.equals("web") && !pkg.startsWith("web.") && imports(read(file), "jakarta.servlet")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void javaSqlIsOnlyUsedByDaoJdbcDbAndBootstrap() {
        Set<String> allowed = Set.of("dao.jdbc", "db", "bootstrap");
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            if (!allowed.contains(relativePackage(MAIN, file)) && imports(read(file), "java.sql")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void objectInputStreamIsOnlyUsedInTheSnapshotPackage() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            if (!relativePackage(MAIN, file).equals("snapshot") && read(file).contains("ObjectInputStream")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders, "never deserialize outside snapshot recovery");
    }

    @Test
    void businessCodeDoesNotReadTheWallClockDirectly() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            if (read(file).contains("System.currentTimeMillis")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders, "inject java.time.Clock instead");
    }

    @Test
    void jspFilesContainNoScriptlets() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(WEBAPP)) {
            for (Path jsp : walk.filter(p -> p.toString().endsWith(".jsp") || p.toString().endsWith(".jspf")).toList()) {
                // allow directives (<%@) and JSP comments (<%--); reject scriptlets, expressions and declarations
                if (Pattern.compile("<%(?![@-])").matcher(read(jsp)).find()) {
                    offenders.add(jsp.toString());
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void everyPackageOfTheDesignHasAPackageInfo() {
        List<String> missing = new ArrayList<>();
        for (String pkg : SDD_PACKAGES) {
            Path info = MAIN.resolve("com").resolve("cloudkitchen").resolve(pkg.replace('.', '/')).resolve("package-info.java");
            if (!Files.isRegularFile(info)) {
                missing.add(ROOT + "." + pkg);
            } else {
                assertTrue(read(info).contains("package " + ROOT + "." + pkg + ";"), info.toString());
            }
        }
        assertEquals(List.of(), missing, "SDD section 3 packages");
    }

    @Test
    void noUnexpectedPackagesExistOutsideTheDesign() {
        Set<String> known = new java.util.HashSet<>(SDD_PACKAGES);
        known.add("web");
        known.add("");
        List<String> unexpected = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String pkg = relativePackage(MAIN, file);
            if (!known.contains(pkg)) {
                unexpected.add(ROOT + "." + pkg);
            }
        }
        assertEquals(List.of(), unexpected.stream().distinct().toList(), "packages must come from SDD section 3");
    }
}
