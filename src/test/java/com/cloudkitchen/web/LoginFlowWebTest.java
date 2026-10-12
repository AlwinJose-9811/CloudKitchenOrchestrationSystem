package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-AUT-01 (web part), FR-AUT-02, FR-WEB-02: the real login form, the generic failure message, PRG and the home redirect. */
class LoginFlowWebTest extends WebTestBase {

    private static final String GENERIC = AuthenticationException.GENERIC_MESSAGE;

    private Reply login(HttpTestClient browser, String username, String password) {
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("password", password);
        form.put("_csrf", token);
        return browser.postForm("/login", form);
    }

    @Test
    void theLoginPageIsAFormWithLabelsAndAHiddenCsrfTokenAndOpensASession() {
        HttpTestClient browser = app.browser();
        Reply page = browser.get("/login");
        assertEquals(200, page.status());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        String body = page.body();
        assertTrue(body.contains("<form method=\"post\" action=\"/login\""), body);
        assertTrue(body.contains("<label for=\"username\">") && body.contains("<label for=\"password\">"));
        assertTrue(body.contains("type=\"password\""));
        assertNotNull(HttpTestClient.extractCsrf(body));
        assertNotNull(browser.cookie("JSESSIONID"), "the session that holds the token is opened by this page");
        assertFalse(body.contains("returnTo"), "no returnTo parameter (open redirect)");
    }

    @Test
    void aValidLoginRedirectsToTheDashboardForBothRolesAndWritesNoMessage() {
        Reply admin = login(app.browser(), app.adminUsername(), app.adminPassword());
        HttpTestClient.assertRedirect(admin, "/dashboard");
        Reply staff = login(app.browser(), app.staffUsername(), app.staffPassword());
        HttpTestClient.assertRedirect(staff, "/dashboard");
        assertEquals("", admin.body());
    }

    @Test
    void aWrongPasswordAnUnknownUserAndEmptyFieldsAllGetTheSameRedirectAndTheSameGenericMessage() {
        String[][] attempts = {
            {app.staffUsername(), "definitely-wrong"},
            {"nobody-" + System.nanoTime(), "definitely-wrong"},
            {app.staffUsername(), ""},
            {"", "x"},
            {"   ", "x"},
        };
        for (String[] attempt : attempts) {
            HttpTestClient browser = app.browser();
            Reply reply = login(browser, attempt[0], attempt[1]);
            HttpTestClient.assertRedirect(reply, "/login");
            Reply next = browser.get("/login");
            assertTrue(next.body().contains(GENERIC), "message for " + attempt[0] + ": " + next.body());
            assertTrue(next.body().contains("flash-error"));
            assertFalse(browser.get("/login").body().contains(GENERIC), "the message is shown once");
        }
    }

    @Test
    void theFailureNeverEchoesTheUsernameOrThePassword() {
        HttpTestClient browser = app.browser();
        login(browser, "probe-user-name", "probe-pass-word");
        String page = browser.get("/login").body();
        assertFalse(page.contains("probe-user-name") || page.contains("probe-pass-word"), page);
    }

    @Test
    void anOverlongFieldIsRefusedWithTheSameGenericMessage() {
        HttpTestClient browser = app.browser();
        Reply reply = login(browser, app.staffUsername(), "x".repeat(5000));
        HttpTestClient.assertRedirect(reply, "/login");
        assertTrue(browser.get("/login").body().contains(GENERIC));
    }

    @Test
    void aLoggedInVisitorOfTheLoginPageGoesToTheDashboardAndTheHomePageFollowsTheSession() {
        HttpTestClient anonymous = app.browser();
        HttpTestClient.assertRedirect(anonymous.get("/"), "/login");
        HttpTestClient staff = app.staffBrowser();
        HttpTestClient.assertRedirect(staff.get("/login"), "/dashboard");
        HttpTestClient.assertRedirect(staff.get("/"), "/dashboard");
    }

    @Test
    void theLoginPostIsPrgAndARefreshOfTheTargetDoesNotResubmitTheCredentials() {
        HttpTestClient browser = app.browser();
        Reply post = login(browser, app.staffUsername(), "wrong");
        assertEquals(302, post.status());
        assertEquals(200, browser.get("/login").status(), "following the redirect is a GET");
        assertEquals(200, browser.get("/login").status());
    }

    @Test
    void afterLoginTheSessionReachesProtectedRoutesAndAnAnonymousClientDoesNot() {
        HttpTestClient staff = app.staffBrowser();
        // /inventory is a protected route with a real page: a valid session reaches it
        assertEquals(200, staff.get("/inventory").status());
        HttpTestClient anonymous = app.browser();
        HttpTestClient.assertRedirect(anonymous.get("/inventory"), "/login");
    }

    @Test
    void theSessionCookieAfterLoginIsStillHttpOnlyAndSameSiteLax() {
        HttpTestClient browser = app.browser();
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        Reply reply = browser.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword(), "_csrf", token));
        String cookie = reply.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"), cookie);
        assertNotEquals("", browser.cookie("JSESSIONID"));
    }
}
