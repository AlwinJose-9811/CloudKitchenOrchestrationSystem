package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import com.cloudkitchen.web.support.RoutePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FR-WEB-03: the synchronizer token on every POST route of the route table (the list comes from {@code RoutePolicy}, so a
 * new POST route is covered automatically), for an anonymous caller, a logged-in staff member and an administrator, with no
 * token, an empty token, a token of another session, a token in a header, and the right token.
 */
class CsrfWebTest extends WebTestBase {

    private static List<RoutePolicy.Route> postRoutes() {
        return RoutePolicy.standard().allRoutes().stream().filter(RoutePolicy.Route::isPost).toList();
    }

    private static Map<String, String> form(String token) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", "1");
        if (token != null) {
            fields.put("_csrf", token);
        }
        return fields;
    }

    @Test
    void thereAreElevenPostRoutesAndEveryOneIsCovered() {
        assertEquals(11, postRoutes().size(), postRoutes().toString());
    }

    @Test
    void everyPostRouteRefusesARequestWithoutAValidTokenWhoeverSendsIt() {
        HttpTestClient anonymous = app.browser();
        HttpTestClient staff = app.staffBrowser();
        HttpTestClient admin = app.adminBrowser();
        String otherSessionToken = HttpTestClient.extractCsrf(app.browser().get("/login").body());
        for (RoutePolicy.Route route : postRoutes()) {
            for (Map.Entry<String, HttpTestClient> actor : Map.of("anonymous", anonymous, "staff", staff, "admin", admin).entrySet()) {
                String what = actor.getKey() + " POST " + route.path();
                HttpTestClient client = actor.getValue();
                assertEquals(403, client.postForm(route.path(), form(null)).status(), what + " without a token");
                assertEquals(403, client.postForm(route.path(), form("")).status(), what + " with an empty token");
                assertEquals(403, client.postForm(route.path(), form("wrong")).status(), what + " with a wrong token");
                assertEquals(403, client.postForm(route.path(), form(otherSessionToken)).status(), what + " with the token of another session");
                assertEquals(403, client.send("POST", route.path(), Map.of("Content-Type", "application/x-www-form-urlencoded",
                        "X-CSRF-Token", "anything", "X-XSRF-TOKEN", "anything"), "id=1").status(), what + " with the token only in a header");
            }
        }
    }

    @Test
    void aRefusedPostChangesNothingAndNeverReachesTheServlet() {
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        // a login with a perfectly valid password but no token is a CSRF failure, not a login: the session stays anonymous
        Reply forged = visitor.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword()));
        assertEquals(403, forged.status());
        HttpTestClient.assertRedirect(visitor.get("/inventory"), "/login");
        // and the account was not touched by the forged attempt: the real login still works
        HttpTestClient.assertRedirect(visitor.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword(), "_csrf", token)),
                "/dashboard");
    }

    @Test
    void withTheRightTokenARequestPassesTheCsrfFilterOnEveryPostRoute() {
        HttpTestClient admin = app.adminBrowser();
        for (RoutePolicy.Route route : postRoutes()) {
            if (route.path().equals("/logout")) {
                continue; // ends the session; covered at the end
            }
            String token = TestApp.tokenOf(admin);
            Reply reply = admin.postForm(route.path(), form(token));
            assertNotEquals(403, reply.status(), "admin POST " + route.path() + " with the right token: " + reply.status());
        }
        String token = TestApp.tokenOf(admin);
        HttpTestClient.assertRedirect(admin.postForm("/logout", form(token)), "/login");
    }

    @Test
    void anAnonymousPostWithAValidTokenIsRedirectedToTheLoginPageNotRefused() {
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        for (RoutePolicy.Route route : postRoutes()) {
            if (!route.authRequired()) {
                continue;
            }
            Reply reply = visitor.postForm(route.path(), form(token));
            HttpTestClient.assertRedirect(reply, "/login");
        }
    }

    @Test
    void everyNonSafeMethodNeedsTheTokenNotOnlyPost() {
        HttpTestClient admin = app.adminBrowser();
        for (String method : List.of("PUT", "DELETE", "PATCH")) {
            assertEquals(403, admin.send(method, "/admin/menu", Map.of(), "x=1").status(), method + " without a token");
        }
        String token = TestApp.tokenOf(admin);
        assertNotEquals(403, admin.send("PUT", "/admin/menu?_csrf=" + token, Map.of(), "x=1").status());
    }

    @Test
    void safeMethodsNeedNoTokenAndHeadAndOptionsAreNotRefused() {
        HttpTestClient admin = app.adminBrowser();
        assertNotEquals(403, admin.get("/admin/menu").status());
        assertNotEquals(403, admin.send("HEAD", "/admin/menu", Map.of(), null).status());
        assertNotEquals(403, admin.send("OPTIONS", "/admin/menu", Map.of(), null).status());
    }

    @Test
    void theRefusalIsTheGenericForbiddenPageWithoutTokenValues() {
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        Reply reply = visitor.postForm("/login", Map.of("username", "x", "password", "y", "_csrf", token + "x"));
        assertEquals(403, reply.status());
        assertTrue(reply.body().contains("<h1>403"), reply.body());
        assertTrue(!reply.body().contains(token), "the expected token is never revealed");
    }

    @Test
    void theTokenIsPerSessionSoOneBrowsersTokenDoesNotWorkInAnother() {
        HttpTestClient one = app.adminBrowser();
        HttpTestClient two = app.adminBrowser();
        String tokenOne = TestApp.tokenOf(one);
        assertEquals(403, two.postForm("/admin/menu", form(tokenOne)).status());
        assertNotEquals(403, one.postForm("/admin/menu", form(tokenOne)).status());
    }

    @Test
    void noRouteOfTheProductionTableIsExemptFromTheCsrfCheck() {
        assertTrue(RoutePolicy.standard().allRoutes().stream().noneMatch(RoutePolicy.Route::csrfExempt),
                "the temporary demonstration route is gone (D-085); only a test route may be exempt");
        HttpTestClient visitor = app.browser();
        assertEquals(403, visitor.post("/demo/orders", "x=1").status(), "a POST to the old demonstration path meets the CSRF check like any other");
    }
}
