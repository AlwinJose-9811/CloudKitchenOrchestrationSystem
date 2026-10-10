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
 * Source-scan architecture rules (Phase 1 rules plus the layering, money, documentation and call-site rules of
 * Phase 2; later phases add the rules listed in TESTING_STRATEGY). Includes the guard for deviation DEV-1: Spring may
 * only appear as a transitive dependency of MariaDB4j.
 *
 * <p>Allow-lists below record the places where SDD section 2.2 and the class catalogue disagree; each is named in
 * the Phase 2 evidence: {@code OrderFactory} (package {@code domain}) uses {@code dto.OrderRequest},
 * {@code dto.OrderItemRequest} and {@code service.MenuCatalog}; {@code ValidationException} uses {@code dto.FieldError};
 * the {@code dao} interfaces take a {@code java.sql.Connection} (SDD 5.2) and so may import {@code Connection} and
 * {@code SQLException}, nothing else from {@code java.sql}.
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
    void javaSqlIsOnlyUsedByDaoJdbcDbAndBootstrapAndTheDaoInterfacesTakeOnlyConnection() {
        Set<String> allowed = Set.of("dao.jdbc", "db", "bootstrap");
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String pkg = relativePackage(MAIN, file);
            String source = read(file);
            if (pkg.equals("dao")) {
                for (String line : source.split("\\R")) {
                    String t = line.strip();
                    if (t.startsWith("import java.sql.") && !t.equals("import java.sql.Connection;")
                            && !t.equals("import java.sql.SQLException;")) {
                        offenders.add(file + ": " + t);
                    }
                }
            } else if (!allowed.contains(pkg) && imports(source, "java.sql")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    // ---- layering (SDD 2.2) ----

    private static final String P = "com.cloudkitchen.";

    /** Every import of one of our own packages in the given package, as "file -> imported type". */
    private static List<String> ownImports(String pkg) {
        List<String> found = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            if (!relativePackage(MAIN, file).equals(pkg)) {
                continue;
            }
            for (String line : read(file).split("\\R")) {
                String t = line.strip();
                if (t.startsWith("import " + P) || t.startsWith("import static " + P)) {
                    found.add(file.getFileName() + " -> " + t.replaceFirst("import (static )?", "").replace(";", ""));
                }
            }
        }
        return found;
    }

    private static void assertImportsOnlyFrom(String pkg, List<String> allowedPrefixes, List<String> exceptions) {
        List<String> offenders = new ArrayList<>();
        for (String imp : ownImports(pkg)) {
            String type = imp.substring(imp.indexOf("-> ") + 3);
            boolean ok = allowedPrefixes.stream().anyMatch(prefix -> samePackageOrSubtype(type, prefix))
                    || exceptions.contains(imp);
            if (!ok) {
                offenders.add(imp);
            }
        }
        assertEquals(List.of(), offenders, "package " + pkg + " may only import " + allowedPrefixes + " (+ " + exceptions + ")");
    }

    /** {@code type} is directly in package {@code prefix} (no sub-package) when prefix ends with a dot-free package name. */
    private static boolean samePackageOrSubtype(String type, String packageName) {
        int dot = type.lastIndexOf('.');
        return dot > 0 && type.substring(0, dot).equals(P + packageName);
    }

    @Test
    void utilDependsOnTheJdkOnly() {
        assertEquals(List.of(), ownImports("util"));
    }

    @Test
    void exceptionsDependOnlyOnExceptionsEnumsAndFieldError() {
        assertImportsOnlyFrom("exception", List.of("exception", "domain.enums"),
                List.of("ValidationException.java -> " + P + "dto.FieldError"));
    }

    @Test
    void domainDependsOnlyOnDomainExceptionsAndUtil() {
        List<String> orderFactory = List.of(
                "OrderFactory.java -> " + P + "dto.OrderRequest",
                "OrderFactory.java -> " + P + "dto.OrderItemRequest",
                "OrderFactory.java -> " + P + "service.MenuCatalog");
        assertImportsOnlyFrom("domain", List.of("domain", "domain.enums", "domain.contract", "exception", "util"), orderFactory);
        assertImportsOnlyFrom("domain.enums", List.of("domain.enums", "exception"), List.of());
        assertImportsOnlyFrom("domain.contract", List.of("domain", "domain.enums"), List.of());
    }

    @Test
    void dtoDependsOnlyOnDomainExceptionsAndUtil() {
        assertImportsOnlyFrom("dto", List.of("dto", "domain", "domain.enums", "exception", "util"), List.of());
    }

    @Test
    void daoInterfacesDependOnlyOnDomainDtoAndExceptions() {
        assertImportsOnlyFrom("dao", List.of("dao", "domain", "domain.enums", "dto", "exception", "util"), List.of());
    }

    @Test
    void servicesDoNotDependOnWebOrOnPipelineBeyondTheThreeAllowedTypes() {
        assertImportsOnlyFrom("service", List.of("service", "dao", "db", "domain", "domain.enums", "domain.contract", "dto",
                "exception", "util", "config", "io"),
                List.of());
    }

    @Test
    void pipelineUsesServicesNotThePersistenceLayer() {
        List<String> offenders = new ArrayList<>();
        for (String imp : ownImports("pipeline")) {
            String type = imp.substring(imp.indexOf("-> ") + 3);
            if (type.startsWith(P + "dao") || type.startsWith(P + "db.") || type.startsWith(P + "web")) {
                offenders.add(imp);
            }
        }
        assertEquals(List.of(), offenders);
        for (Path file : javaFiles(MAIN)) {
            if (relativePackage(MAIN, file).equals("pipeline") && imports(read(file), "java.sql")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void servicesOnlyUseTheThreeAllowedPipelineTypes() {
        Set<String> allowed = Set.of(P + "pipeline.OrderDispatcher", P + "pipeline.InFlightRegistry", P + "pipeline.MetricsRegistry");
        List<String> offenders = new ArrayList<>();
        for (String imp : ownImports("service")) {
            String type = imp.substring(imp.indexOf("-> ") + 3);
            if (type.startsWith(P + "pipeline.") && !allowed.contains(type)) {
                offenders.add(imp);
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void persistenceDoesNotDependOnServiceWebOrPipeline() {
        List<String> offenders = new ArrayList<>();
        for (String pkg : List.of("dao", "dao.jdbc", "db")) {
            for (String imp : ownImports(pkg)) {
                String type = imp.substring(imp.indexOf("-> ") + 3);
                if (type.startsWith(P + "service.") || type.startsWith(P + "web.") || type.startsWith(P + "pipeline.")) {
                    offenders.add(pkg + ": " + imp);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void daoCodeNeverControlsTransactionsOrClosesTheConnection() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String pkg = relativePackage(MAIN, file);
            if (pkg.equals("dao") || pkg.equals("dao.jdbc")) {
                String code = stripComments(read(file));
                if (Pattern.compile("\\.(commit|rollback|setAutoCommit)\\s*\\(").matcher(code).find()) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "transactions only through Database.inTransaction");
    }

    // ---- money, documentation ----

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void noFloatingPointTypesInDomainServiceDaoOrDto() {
        List<String> offenders = new ArrayList<>();
        Set<String> checked = Set.of("domain", "domain.enums", "domain.contract", "service", "dao", "dao.jdbc", "dto", "util");
        for (Path file : javaFiles(MAIN)) {
            if (checked.contains(relativePackage(MAIN, file)) && !file.endsWith("MetricsView.java")
                    && Pattern.compile("\\b(double|float|Double|Float)\\b").matcher(stripComments(read(file))).find()) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders, "money is BigDecimal; MetricsView.averageProcessingMillis is the one non-money double");
    }

    @Test
    void everyPublicTopLevelTypeHasJavadoc() {
        Pattern declaration = Pattern.compile("(?m)^public\\s+(?:abstract\\s+|final\\s+|sealed\\s+)*(class|interface|enum|record)\\s+\\w+");
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            if (file.getFileName().toString().equals("package-info.java")) {
                continue;
            }
            String source = read(file);
            java.util.regex.Matcher m = declaration.matcher(source);
            if (m.find()) {
                String before = source.substring(0, m.start()).stripTrailing();
                // annotations such as @FunctionalInterface may sit between the comment and the declaration
                before = before.replaceAll("(?s)(\\s*@\\w+(\\([^)]*\\))?)+$", "").stripTrailing();
                if (!before.endsWith("*/")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "Javadoc on public types");
    }

    @Test
    void everyInterruptedExceptionHandlerRestoresTheFlag() {
        List<String> offenders = new ArrayList<>();
        Pattern handler = Pattern.compile("catch\\s*\\([^)]*InterruptedException[^)]*\\)\\s*\\{");
        for (Path file : javaFiles(MAIN)) {
            String code = stripComments(read(file));
            java.util.regex.Matcher m = handler.matcher(code);
            while (m.find()) {
                int depth = 1;
                int i = m.end();
                while (i < code.length() && depth > 0) {
                    char ch = code.charAt(i++);
                    depth += ch == '{' ? 1 : ch == '}' ? -1 : 0;
                }
                String body = code.substring(m.end(), i);
                if (!body.contains(".interrupt()") && !body.contains("throw ")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "restore the interrupt flag or propagate");
    }

    // ---- call-site restrictions of SDD 7.12.5 (bite as the classes appear in later phases) ----

    /** method token -> the only source files (by name) that may mention it. */
    private static final java.util.Map<String, Set<String>> CALL_SITES = java.util.Map.of(
            "claimStockRestore", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java"),
            "markCompensationPending", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java"),
            "recordCompensationFailure", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java"),
            "expireLeasesOf", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java", "OrderOrchestrator.java",
                    "WorkerMonitor.java", "RecoveryService.java"),
            "clearAllLeases", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java", "OrderOrchestrator.java",
                    "WorkerMonitor.java", "RecoveryService.java"),
            "acquireLease", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java", "StationWorker.java"),
            "renewLease", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java", "StationWorker.java"),
            "releaseLease", Set.of("OrderService.java", "OrderDao.java", "JdbcOrderDao.java", "StationWorker.java"),
            "transitionAsOwner", Set.of("OrderService.java", "StationWorker.java"),
            "failAsOwner", Set.of("OrderService.java", "StationWorker.java"));

    @Test
    void restrictedMethodsAreMentionedOnlyByTheirAllowedCallers() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String code = stripComments(read(file));
            for (var rule : CALL_SITES.entrySet()) {
                if (Pattern.compile("\\b" + rule.getKey() + "\\b").matcher(code).find()
                        && !rule.getValue().contains(file.getFileName().toString())) {
                    offenders.add(file.getFileName() + " mentions " + rule.getKey());
                }
            }
        }
        assertEquals(List.of(), offenders, "SDD 7.12.5 call-site restrictions");
    }

    @Test
    void stationWorkerNeverCallsTheNonOwnerApi() {
        Path worker = MAIN.resolve("com/cloudkitchen/pipeline/StationWorker.java");
        if (!Files.isRegularFile(worker)) {
            return; // arrives in Phase 4; the rule bites from then on
        }
        String code = stripComments(read(worker));
        List<String> offenders = new ArrayList<>();
        for (String forbidden : List.of("transition", "failOrder", "cancelOrder", "compensate", "completePendingCompensation",
                "retryCompensation", "reclaimLeasesOf", "clearAllLeases")) {
            if (Pattern.compile("\\.\\s*" + forbidden + "\\s*\\(").matcher(code).find()) {
                offenders.add(forbidden);
            }
        }
        assertEquals(List.of(), offenders, "StationWorker may use only the owner-fenced API (F-6)");
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
