package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-AUT-02, FR-AUT-03, FR-AUT-04: session fixation, token rotation, logout invalidation and no session persistence. */
class SessionSecurityWebTest extends WebTestBase {

    @Test
    void theSessionIdAndTheCsrfTokenChangeAtLoginAndTheOldIdIsDead() {
        HttpTestClient browser = app.browser();
        String tokenBefore = HttpTestClient.extractCsrf(browser.get("/login").body());
        String idBefore = browser.cookie("JSESSIONID");
        assertNotNull(idBefore);
        Reply login = browser.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword(), "_csrf", tokenBefore));
        HttpTestClient.assertRedirect(login, "/dashboard");
        String idAfter = browser.cookie("JSESSIONID");
        assertNotNull(idAfter);
        assertNotEquals(idBefore, idAfter, "session fixation: the id presented before login must not survive it");

        // an attacker who planted the old id gets nothing
        HttpTestClient attacker = HttpTestClient.withCookies(app.port());
        attacker.setCookie("JSESSIONID", idBefore);
        HttpTestClient.assertRedirect(attacker.get("/inventory"), "/login");

        // the token that was valid on the login page is useless afterwards; the new one works
        Reply withOldToken = browser.postForm("/orders/cancel", Map.of("_csrf", tokenBefore, "id", "1"));
        assertEquals(403, withOldToken.status(), "the CSRF token is rotated at login");
        String tokenAfter = TestApp.tokenOf(browser);
        assertNotEquals(tokenBefore, tokenAfter);
        assertNotEquals(403, browser.postForm("/orders/cancel", Map.of("_csrf", tokenAfter, "id", "1")).status());
    }

    @Test
    void aLoginMadeWhileAlreadyHoldingASessionAlsoReplacesTheId() {
        HttpTestClient browser = app.staffBrowser();
        String first = browser.cookie("JSESSIONID");
        String token = TestApp.tokenOf(browser);
        Reply again = browser.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword(), "_csrf", token));
        HttpTestClient.assertRedirect(again, "/dashboard");
        assertNotEquals(first, browser.cookie("JSESSIONID"));
    }

    @Test
    void logoutIsAPostWithATokenAndEndsTheSessionEverywhere() {
        HttpTestClient browser = app.staffBrowser();
        String cookie = browser.cookie("JSESSIONID");
        String token = TestApp.tokenOf(browser);
        Reply logout = browser.postForm("/logout", Map.of("_csrf", token));
        HttpTestClient.assertRedirect(logout, "/login");

        HttpTestClient replay = HttpTestClient.withCookies(app.port());
        replay.setCookie("JSESSIONID", cookie);
        HttpTestClient.assertRedirect(replay.get("/inventory"), "/login");
        assertTrue(browser.get("/login").body().contains("You have been signed out."), "the confirmation lives in a fresh session");
        HttpTestClient.assertRedirect(browser.get("/inventory"), "/login");
    }

    @Test
    void logoutWithoutAValidTokenIsRefusedAndDoesNotEndTheSession() {
        HttpTestClient browser = app.staffBrowser();
        assertEquals(403, browser.post("/logout", "").status());
        assertEquals(403, browser.postForm("/logout", Map.of("_csrf", "not-the-token")).status());
        assertEquals(200, browser.get("/inventory").status(), "the session is still valid");
    }

    @Test
    void aGetOfLogoutDoesNotLogOutAndIsNotAnAllowedRoute() {
        HttpTestClient staff = app.staffBrowser();
        assertEquals(403, staff.get("/logout").status(), "GET /logout is not a route: protected and ADMIN-only");
        assertEquals(200, staff.get("/inventory").status());
        HttpTestClient admin = app.adminBrowser();
        assertEquals(405, admin.get("/logout").status());
        assertEquals(200, admin.get("/inventory").status());
    }

    @Test
    void logoutOfAnAnonymousVisitorWithAValidTokenJustGoesToTheLoginPage() {
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/logout", Map.of("_csrf", token)), "/login");
    }

    @Test
    void theSessionIsNotSharedBetweenTwoBrowsers() {
        HttpTestClient one = app.staffBrowser();
        HttpTestClient two = app.browser();
        assertNotEquals(one.cookie("JSESSIONID"), two.cookie("JSESSIONID"));
        HttpTestClient.assertRedirect(two.get("/inventory"), "/login");
        assertEquals(200, one.get("/inventory").status());
    }

    @Test
    void sessionsAreNeverPersistedToDiskAcrossARestart() throws Exception {
        assertTrue(app.sessionManagerPathname() == null || app.sessionManagerPathname().isEmpty(),
                "META-INF/context.xml switches the persistence file off (D-082): " + app.sessionManagerPathname());
        String[] sameAccounts = {"bootstrap.admin.username", app.adminUsername(), "bootstrap.admin.password", app.adminPassword(),
                "bootstrap.staff.username", app.staffUsername(), "bootstrap.staff.password", app.staffPassword()};
        TestApp second = TestApp.start(db, sameAccounts);
        HttpTestClient browser = second.staffBrowser();
        assertEquals(200, browser.get("/inventory").status(), "a live session exists when the application stops");
        String cookie = browser.cookie("JSESSIONID");
        second.close();
        assertFalse(second.sessionFileWasWritten(), "no SESSIONS.ser may be written at shutdown");

        // a new application knows nothing of the old session even if the same cookie is presented
        TestApp third = TestApp.start(db, sameAccounts);
        try {
            HttpTestClient replay = HttpTestClient.withCookies(third.port());
            replay.setCookie("JSESSIONID", cookie);
            HttpTestClient.assertRedirect(replay.get("/inventory"), "/login");
        } finally {
            third.close();
        }
    }

    @Test
    void theDeploymentDescriptorOfTheWarCarriesTheNoPersistenceSetting() throws Exception {
        String context = Files.readString(Path.of("src", "main", "webapp", "META-INF", "context.xml"));
        assertTrue(context.contains("<Manager pathname=\"\""), context);
    }
}
