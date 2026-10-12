package com.cloudkitchen.web.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.web.support.RoutePolicy.Route;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The single route table (SDD 10.2): it is checked against the SDD table itself, so the two cannot drift apart. */
class RoutePolicyTest {

    private static final RoutePolicy POLICY = RoutePolicy.standard();

    private record Row(int number, String path, String method, boolean auth, String role) {
    }

    /** The rows of the table in SDD section 10.2, read from the document. */
    private static List<Row> sddRows() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("docs", "SDD.md"));
        int start = lines.indexOf("### 10.2 Routes table");
        List<Row> rows = new ArrayList<>();
        for (int i = start; i < lines.size() && !lines.get(i).startsWith("### 10.3"); i++) {
            String line = lines.get(i);
            if (!line.matches("\\| \\d+ \\| `.*")) {
                continue;
            }
            String[] cells = line.split("\\|");
            rows.add(new Row(Integer.parseInt(cells[1].trim()), cells[2].trim().replace("`", ""), cells[3].trim(),
                    cells[5].trim().equals("yes"), cells[6].trim()));
        }
        return rows;
    }

    @Test
    void everyRowOfTheSddTableIsInThePolicyWithTheSameMethodAuthenticationAndRole() throws IOException {
        List<Row> rows = sddRows();
        assertTrue(rows.size() >= 28, "the SDD table has 29 rows, found " + rows.size());
        for (Row row : rows) {
            if (row.number() == 25) {
                assertTrue(POLICY.find("/track", "GET").isEmpty(), "the COULD route /track is not in the table until it is implemented");
                continue;
            }
            String method = row.method().equals("any") ? "*" : row.method();
            Route route = POLICY.allRoutes().stream().filter(r -> r.number() == row.number()).findFirst()
                    .orElseThrow(() -> new AssertionError("row " + row.number() + " is missing"));
            assertEquals(row.path(), route.path(), "row " + row.number());
            assertEquals(method, route.method(), "row " + row.number());
            assertEquals(row.auth(), route.authRequired(), "row " + row.number());
            Set<Role> expected = switch (row.role()) {
                case "public" -> EnumSet.noneOf(Role.class);
                case "ANY" -> EnumSet.allOf(Role.class);
                case "ADMIN" -> EnumSet.of(Role.ADMIN);
                default -> throw new AssertionError("unknown role cell " + row.role());
            };
            assertEquals(expected, route.roles(), "row " + row.number());
        }
    }

    @Test
    void thePolicyHasNoRouteThatTheSddTableLacksExceptTheHealthProbe() throws IOException {
        Set<Integer> sdd = new HashSet<>();
        sddRows().forEach(r -> sdd.add(r.number()));
        for (Route route : POLICY.allRoutes()) {
            assertTrue(route.number() <= 0 || sdd.contains(route.number()), "route " + route.number() + " " + route.path());
        }
        assertEquals("/health", POLICY.allRoutes().stream().filter(r -> r.number() == 0).findFirst().orElseThrow().path());
        assertTrue(POLICY.allRoutes().stream().noneMatch(RoutePolicy.Route::csrfExempt), "no production route is exempt from the CSRF check (D-085)");
        assertTrue(POLICY.allRoutes().stream().noneMatch(r -> r.tier() == RoutePolicy.Tier.TEMPORARY));
    }

    @Test
    void publicRoutesAreExactlyTheStartPageLoginStaticErrorAndHealth() {
        List<String> open = new ArrayList<>();
        for (Route route : POLICY.allRoutes()) {
            if (!route.authRequired()) {
                open.add(route.method() + " " + route.path());
            }
        }
        assertEquals(List.of("GET /", "GET /login", "POST /login", "GET /static/*", "* /error", "GET /health"), open);
    }

    @Test
    void anonymousAndWrongRoleDecisionsFollowTheTable() {
        assertTrue(POLICY.accessFor("/dashboard", "GET").allows(Role.KITCHEN_STAFF));
        assertTrue(POLICY.accessFor("/admin/menu", "GET").allows(Role.ADMIN));
        assertFalse(POLICY.accessFor("/admin/menu", "GET").allows(Role.KITCHEN_STAFF));
        assertFalse(POLICY.accessFor("/admin/compensations", "POST").allows(Role.KITCHEN_STAFF));
        assertEquals(Set.of(), POLICY.requiredRoles("/login", "POST"));
        assertEquals(EnumSet.of(Role.ADMIN), POLICY.requiredRoles("/admin/stock", "POST"));
        assertTrue(POLICY.isPublic("/login", "GET"));
        assertFalse(POLICY.isPublic("/dashboard", "GET"));
    }

    @Test
    void anUnmappedPathOrMethodIsProtectedAndAdminOnly() {
        RoutePolicy.Access unknownPath = POLICY.accessFor("/not-in-the-table", "GET");
        assertFalse(unknownPath.known());
        assertTrue(unknownPath.authRequired());
        assertEquals(EnumSet.of(Role.ADMIN), unknownPath.roles());
        assertFalse(unknownPath.allows(Role.KITCHEN_STAFF));
        assertFalse(POLICY.accessFor("/dashboard", "POST").known(), "POST /dashboard is not a route");
        assertFalse(POLICY.accessFor("/login", "PUT").known());
        assertFalse(POLICY.isPublic("/not-in-the-table", "GET"));
        assertFalse(POLICY.accessFor("/staticfiles/x", "GET").known(), "only the exact prefix /static/ is public");
    }

    @Test
    void theRetiredDemoRouteIsUnmappedProtectedAndAdminOnly() {
        for (String method : List.of("GET", "POST", "PUT")) {
            assertFalse(POLICY.accessFor("/demo/orders", method).known(), method);
            assertFalse(POLICY.isPublic("/demo/orders", method), method);
            assertEquals(EnumSet.of(Role.ADMIN), POLICY.requiredRoles("/demo/orders/extra", method), method);
        }
    }

    @Test
    void aPolicyCanBeExtendedForTestsWithoutChangingTheStandardOne() {
        RoutePolicy extended = POLICY.withRoutes(List.of(new Route(-100, "/__x/*", "*", false, Set.of(), RoutePolicy.Tier.MUST)));
        assertTrue(extended.isPublic("/__x/a", "GET"));
        assertFalse(POLICY.isPublic("/__x/a", "GET"));
        assertEquals(POLICY.allRoutes().size() + 1, extended.allRoutes().size());
    }

    @Test
    void headIsTreatedAsGetAndMethodsAreCaseInsensitive() {
        assertTrue(POLICY.find("/dashboard", "HEAD").isPresent());
        assertTrue(POLICY.find("/dashboard", "get").isPresent());
        assertTrue(POLICY.find("/logout", "HEAD").isEmpty(), "logout is POST only");
    }

    @Test
    void staticFilesMatchByPrefixAndTheErrorRouteMatchesEveryMethod() {
        assertTrue(POLICY.isPublic("/static/app.css", "GET"));
        assertTrue(POLICY.isPublic("/static/deep/er/x.png", "HEAD"));
        assertFalse(POLICY.isPublic("/static/app.css", "POST"));
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            assertTrue(POLICY.isPublic("/error", method), method);
        }
    }

    @Test
    void healthAndStaticFilesNeverOpenASessionButNothingElseIsExempt() {
        assertTrue(POLICY.createsNoSession("/health", "GET"));
        assertTrue(POLICY.createsNoSession("/health", "HEAD"));
        assertTrue(POLICY.createsNoSession("/static/app.css", "GET"));
        assertFalse(POLICY.createsNoSession("/login", "GET"), "the login page needs the session for its token");
        assertFalse(POLICY.createsNoSession("/static/app.css", "POST"), "no POST is ever exempt (D-074)");
        assertFalse(POLICY.createsNoSession("/health", "POST"));
        assertFalse(POLICY.createsNoSession("/dashboard", "GET"));
    }

    @Test
    void everyPostRouteIsListedAndNumberedUniquely() {
        List<String> posts = POLICY.allRoutes().stream().filter(Route::isPost).map(Route::path).toList();
        assertEquals(List.of("/login", "/logout", "/orders/new", "/orders/cancel", "/admin/menu", "/admin/stock", "/admin/simulator",
                "/admin/workers", "/admin/snapshot", "/admin/users", "/admin/compensations"), posts);
        Set<Integer> numbers = new HashSet<>();
        POLICY.allRoutes().forEach(r -> assertTrue(numbers.add(r.number()), "duplicate route number " + r.number()));
    }
}
