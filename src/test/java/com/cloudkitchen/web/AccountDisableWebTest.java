package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * FR-AUT-10 (and FR-AUT-11 for the service call): a disabled account is rejected on the very next GET and POST, its session
 * is invalidated, it cannot log in again, a re-enabled account does not revive old sessions, and a database failure while
 * checking a session fails closed with the generic 500 page.
 */
class AccountDisableWebTest extends WebTestBase {

    private long userId(String username) throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("SELECT id FROM staff_users WHERE username = ?")) {
            p.setString(1, username);
            try (var rs = p.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private void setEnabled(String actor, String target, boolean enabled) throws Exception {
        app.services().auth().setEnabled(userId(actor), userId(target), enabled);
    }

    @AfterEach
    void enableEverybody() throws Exception {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("UPDATE staff_users SET enabled = 1")) {
            p.executeUpdate();
        }
    }

    @Test
    void aDisabledUsersExistingSessionIsRejectedOnTheNextGetAndInvalidated() throws Exception {
        HttpTestClient staff = app.staffBrowser();
        assertEquals(200, staff.get("/inventory").status(), "the session works before the account is disabled");
        String cookie = staff.cookie("JSESSIONID");
        setEnabled(app.adminUsername(), app.staffUsername(), false);

        Reply rejected = staff.get("/inventory");
        HttpTestClient.assertRedirect(rejected, "/login");
        assertTrue(staff.get("/login").body().contains("Please sign in again."), "the login page explains why");
        assertNotEquals(cookie, staff.cookie("JSESSIONID"), "the old session was invalidated; the visitor holds a new, anonymous one");

        HttpTestClient replay = HttpTestClient.withCookies(app.port());
        replay.setCookie("JSESSIONID", cookie);
        HttpTestClient.assertRedirect(replay.get("/inventory"), "/login");
        assertEquals(200, staff.get("/login").status(), "and the login page does not send a disabled visitor to the dashboard");
    }

    @Test
    void aDisabledUsersExistingSessionIsRejectedOnThePostWithAValidTokenAndNothingChanges() throws Exception {
        HttpTestClient staff = app.staffBrowser();
        String token = TestApp.tokenOf(staff);
        setEnabled(app.adminUsername(), app.staffUsername(), false);
        Reply reply = staff.postForm("/orders/cancel", Map.of("_csrf", token, "id", "1"));
        HttpTestClient.assertRedirect(reply, "/login");
        assertEquals("", reply.body());
    }

    @Test
    void aDisabledUserCannotLogInAndGetsTheSameGenericMessage() throws Exception {
        setEnabled(app.adminUsername(), app.staffUsername(), false);
        HttpTestClient browser = app.browser();
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        Reply reply = browser.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword(), "_csrf", token));
        HttpTestClient.assertRedirect(reply, "/login");
        assertTrue(browser.get("/login").body().contains(AuthenticationException.GENERIC_MESSAGE));
    }

    @Test
    void reEnablingTheAccountDoesNotReviveOldSessionsButAllowsANewLogin() throws Exception {
        HttpTestClient old = app.staffBrowser();
        String oldCookie = old.cookie("JSESSIONID");
        setEnabled(app.adminUsername(), app.staffUsername(), false);
        setEnabled(app.adminUsername(), app.staffUsername(), true);

        HttpTestClient replay = HttpTestClient.withCookies(app.port());
        replay.setCookie("JSESSIONID", oldCookie);
        HttpTestClient.assertRedirect(replay.get("/inventory"), "/login");
        HttpTestClient fresh = app.staffBrowser();
        assertEquals(200, fresh.get("/inventory").status());
    }

    @Test
    void theAdministratorsOwnSessionWorksWhileAStaffAccountIsDisabled() throws Exception {
        HttpTestClient admin = app.adminBrowser();
        setEnabled(app.adminUsername(), app.staffUsername(), false);
        assertEquals(404, admin.get("/admin/unknown").status());
    }

    @Test
    void anAdministratorCannotDisableThemselvesAndStaysLoggedIn() throws Exception {
        long admin = userId(app.adminUsername());
        CloudKitchenException self = org.junit.jupiter.api.Assertions.assertThrows(CloudKitchenException.class,
                () -> app.services().auth().setEnabled(admin, admin, false));
        assertFalse(self.getMessage().isEmpty());
        HttpTestClient session = app.adminBrowser();
        assertEquals(404, session.get("/admin/unknown").status(), "the refused attempt left the administrator logged in");
    }

    @Test
    void aFailureToCheckTheSessionFailsClosedWithTheGeneric500PageAndServesNothing() throws Exception {
        HttpTestClient staff = app.staffBrowser();
        assertEquals(200, staff.get("/inventory").status());
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("RENAME TABLE staff_users TO staff_users_hidden")) {
            p.executeUpdate();
        }
        try {
            Reply reply = staff.get("/inventory");
            assertEquals(500, reply.status(), "fail closed: the request is not served");
            assertTrue(reply.body().contains("<h1>500"), reply.body());
            for (String word : new String[] {"Exception", "SQL", "staff_users", "at com.", "java."}) {
                assertFalse(reply.body().contains(word), "leaks " + word);
            }
            Reply post = staff.postForm("/orders/cancel", Map.of("_csrf", TestApp.tokenOf(staff), "id", "1"));
            assertEquals(500, post.status());
        } finally {
            try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement("RENAME TABLE staff_users_hidden TO staff_users")) {
                p.executeUpdate();
            }
        }
        assertEquals(200, staff.get("/inventory").status(), "the session is valid again once the database is");
    }

    @Test
    void theSessionStateMatchesTheServiceView() throws Exception {
        SessionUser fresh = app.services().auth().authenticate(app.staffUsername(), app.staffPassword().toCharArray());
        setEnabled(app.adminUsername(), app.staffUsername(), false);
        assertTrue(app.services().auth().validateSession(fresh).isEmpty());
    }
}
