package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import com.cloudkitchen.web.support.RoutePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FR-AUT-06: the access matrix. For every route of the table and for an anonymous caller, a KITCHEN_STAFF user and an ADMIN
 * the answer must be the one the SDD specifies: a protected route redirects the anonymous caller to /login, refuses the
 * wrong role with 403 and lets the right role through the filters (what the servlet then answers is not the question
 * here; most servlets arrive in later checkpoints, so a pass shows as 404 or 405 from the container).
 */
class RoleAccessWebTest extends WebTestBase {

    private enum Actor { ANONYMOUS, STAFF, ADMIN }

    private HttpTestClient client(Actor actor) {
        return switch (actor) {
            case ANONYMOUS -> app.browser();
            case STAFF -> app.staffBrowser();
            case ADMIN -> app.adminBrowser();
        };
    }

    private static String concretePath(RoutePolicy.Route route) {
        return route.path().endsWith("/*") ? route.path().substring(0, route.path().length() - 1) + "app.css" : route.path();
    }

    private static boolean isLoginRedirect(Reply reply) {
        return reply.status() == 302 && reply.location() != null && reply.location().endsWith("/login");
    }

    private Reply request(HttpTestClient browser, RoutePolicy.Route route) {
        String method = route.method().equals("*") ? "GET" : route.method();
        String path = concretePath(route);
        if (method.equals("POST")) {
            return browser.postForm(path, Map.of("_csrf", TestApp.tokenOf(browser), "id", "1"));
        }
        return browser.get(path);
    }

    private static boolean allowed(RoutePolicy.Route route, Actor actor) {
        if (!route.authRequired()) {
            return true;
        }
        return switch (actor) {
            case ANONYMOUS -> false;
            case STAFF -> route.roles().contains(Role.KITCHEN_STAFF);
            case ADMIN -> route.roles().contains(Role.ADMIN);
        };
    }

    @Test
    void everyRouteGivesEveryActorTheAnswerTheRouteTableSpecifies() {
        List<String> failures = new ArrayList<>();
        for (Actor actor : Actor.values()) {
            HttpTestClient browser = client(actor);
            for (RoutePolicy.Route route : RoutePolicy.standard().allRoutes()) {
                if (route.path().equals("/logout") || (route.path().equals("/login") && route.isPost())) {
                    continue; // these two end or replace the session; they have their own tests below
                }
                Reply reply = request(browser, route);
                String what = actor + " " + route.method() + " " + route.path() + " -> " + reply.status() + " " + reply.location();
                if (allowed(route, actor)) {
                    if (reply.status() == 403 || (route.authRequired() && isLoginRedirect(reply))) {
                        failures.add("should pass the filters: " + what);
                    }
                } else if (actor == Actor.ANONYMOUS) {
                    if (!isLoginRedirect(reply)) {
                        failures.add("should redirect to /login: " + what);
                    }
                } else if (reply.status() != 403) {
                    failures.add("should be 403: " + what);
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theRoutesThatShouldAnswerForRealDoSo() {
        assertEquals(200, app.browser().get("/login").status());
        assertEquals(200, app.browser().get("/health").status());
        assertEquals(200, app.browser().get("/static/app.css").status());
        assertEquals(404, app.browser().get("/error").status());
        HttpTestClient.assertRedirect(app.browser().get("/"), "/login");
        for (Actor actor : List.of(Actor.STAFF, Actor.ADMIN)) {
            HttpTestClient.assertRedirect(client(actor).get("/"), "/dashboard");
        }
    }

    @Test
    void anonymousLogoutAndLoginPostsBehaveAsSpecified() {
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/logout", Map.of("_csrf", token)), "/login");
        HttpTestClient.assertRedirect(visitor.postForm("/login", Map.of("username", "x", "password", "y", "_csrf", token)), "/login");
    }

    @Test
    void aPathThatIsNotInTheTableIsRedirectedForAnonymousForbiddenForStaffAndOnlyTheAdminReachesTheContainer() {
        for (String path : List.of("/nothing", "/admin/unknown", "/orders/export", "/dashboard/extra")) {
            HttpTestClient.assertRedirect(app.browser().get(path), "/login");
        }
        assertEquals(400, app.browser().get("/%2e%2e/admin").status(), "the container itself refuses an encoded traversal");
        HttpTestClient staff = app.staffBrowser();
        HttpTestClient admin = app.adminBrowser();
        for (String path : List.of("/nothing", "/admin/unknown", "/orders/export", "/dashboard/extra")) {
            assertEquals(403, staff.get(path).status(), "staff " + path);
            assertEquals(404, admin.get(path).status(), "admin " + path);
        }
    }

    @Test
    void aRouteWithTheWrongMethodIsTreatedAsUnmapped() {
        HttpTestClient staff = app.staffBrowser();
        assertEquals(403, staff.postForm("/dashboard", Map.of("_csrf", TestApp.tokenOf(staff))).status(), "POST /dashboard is not a route");
        HttpTestClient admin = app.adminBrowser();
        assertEquals(405, admin.postForm("/dashboard", Map.of("_csrf", TestApp.tokenOf(admin))).status(), "the administrator reaches the dashboard servlet, which has no POST");
    }

    @Test
    void pathTricksDoNotBypassTheTable() {
        HttpTestClient anonymous = app.browser();
        for (String path : List.of("/admin/menu;x=1", "//admin/menu", "/admin/./menu", "/admin/menu/", "/ADMIN/menu", "/admin/%6denu", "/admin/menu%00", "/orders/../admin/menu")) {
            Reply reply = anonymous.get(path);
            assertTrue(isLoginRedirect(reply) || reply.status() == 400 || reply.status() == 404,
                    path + " must not reach an administrator page anonymously: " + reply.status() + " " + reply.location());
            assertFalse(reply.status() == 200, path);
        }
        HttpTestClient staff = app.staffBrowser();
        for (String path : List.of("/admin/menu;x=1", "/admin/./menu", "/admin/%6denu")) {
            Reply reply = staff.get(path);
            assertTrue(reply.status() == 403 || reply.status() == 400 || reply.status() == 404, path + " -> " + reply.status());
        }
    }

    @Test
    void theRetiredDemonstrationRouteIsGoneAndNothingBelowDemoIsPublic() {
        assertTrue(RoutePolicy.standard().allRoutes().stream().noneMatch(r -> r.path().startsWith("/demo")), "no /demo route is left in the table (D-085)");
        HttpTestClient.assertRedirect(app.browser().get("/demo/orders"), "/login");
        HttpTestClient.assertRedirect(app.browser().get("/demo/orders/extra"), "/login");
        HttpTestClient staff = app.staffBrowser();
        assertEquals(403, staff.get("/demo/orders").status());
        HttpTestClient admin = app.adminBrowser();
        assertEquals(404, admin.get("/demo/orders").status(), "the page no longer exists, even for the administrator");
        assertEquals(404, admin.postForm("/demo/orders", Map.of("_csrf", TestApp.tokenOf(admin))).status());
        assertTrue(app.browser().get("/demo/orders").headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void theRoleComesFromTheDatabaseOnEveryRequestNotFromTheSession() throws Exception {
        HttpTestClient admin = app.adminBrowser();
        assertEquals(404, admin.get("/admin/unknown").status(), "an administrator reaches the container");
        try (java.sql.Connection c = db.connection(); java.sql.PreparedStatement p = c.prepareStatement(
                "UPDATE staff_users SET user_role = 'KITCHEN_STAFF' WHERE username = ?")) {
            p.setString(1, app.adminUsername());
            assertEquals(1, p.executeUpdate());
        }
        try {
            assertEquals(403, admin.get("/admin/unknown").status(), "the demotion applies to the existing session on the next request");
            assertEquals(200, admin.get("/inventory").status(), "and the session itself stays valid for staff routes");
        } finally {
            try (java.sql.Connection c = db.connection(); java.sql.PreparedStatement p = c.prepareStatement(
                    "UPDATE staff_users SET user_role = 'ADMIN' WHERE username = ?")) {
                p.setString(1, app.adminUsername());
                p.executeUpdate();
            }
        }
        assertEquals(404, admin.get("/admin/unknown").status(), "the promotion applies as well");
    }
}
