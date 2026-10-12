package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-AUT-03 and FR-AUT-08 (descriptor and listener part) and D-074: the session cookie attributes, the 30-minute
 * timeout, no URL-based session ids, and probes, static files and error pages that never open a session.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionPolicyWebTest {

    private DbFixture db;
    private TestApp app;
    private TestApp secureApp;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(Clock.systemUTC());
        app = TestApp.start(db);
    }

    @AfterAll
    void stop() {
        if (secureApp != null) {
            secureApp.close();
        }
        if (app != null) {
            app.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static String sessionCookie(Reply reply) {
        return reply.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElse(null);
    }

    @Test
    void theSessionCookieIsHttpOnlySameSiteLaxAndNotSecureByDefault() {
        Reply reply = new HttpTestClient(app.port()).get("/__test/session");
        String cookie = sessionCookie(reply);
        assertTrue(cookie != null, reply.headers().toString());
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        assertFalse(cookie.contains("Secure"), "Secure only when server.session.cookie.secure=true: " + cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
    }

    @Test
    void theSessionTimesOutAfter30MinutesOfInactivity() {
        assertEquals("maxInactive=1800", new HttpTestClient(app.port()).get("/__test/session").body());
    }

    @Test
    void theSecureFlagFollowsTheConfiguration() throws Exception {
        secureApp = TestApp.start(db, "server.session.cookie.secure", "true");
        String cookie = sessionCookie(new HttpTestClient(secureApp.port()).get("/__test/session"));
        assertTrue(cookie != null && cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"), cookie);
    }

    @Test
    void theSessionIdIsNeverPutInAUrl() {
        // a session exists after this call; the links on the next page must stay plain even though the cookie is sent
        HttpTestClient browser = app.browser();
        browser.get("/__test/session");
        String body = browser.get("/__test/render").body();
        assertFalse(body.toLowerCase().contains("jsessionid"), body);
        // and without any cookie at all the first page does not rewrite its links either
        String cookieless = new HttpTestClient(app.port()).get("/__test/render").body();
        assertFalse(cookieless.toLowerCase().contains("jsessionid"), cookieless);
    }

    @Test
    void healthStaticFilesAndErrorPagesNeverCreateASession() {
        HttpTestClient http = new HttpTestClient(app.port());
        for (String path : List.of("/health", "/static/app.css", "/static/missing.css", "/no-such-page", "/__test/boom", "/__test/params?n=x")) {
            assertEquals(List.of(), http.get(path).headers().allValues("Set-Cookie"), path + " must not open a session");
        }
        assertEquals(List.of(), http.post("/health", "").headers().allValues("Set-Cookie"), "a refused POST opens no session either");
    }

    @Test
    void aSessionIsCreatedOnlyByTheCodeThatNeedsOne() {
        HttpTestClient http = new HttpTestClient(app.port());
        assertTrue(sessionCookie(http.get("/__test/session")) != null);
        assertTrue(sessionCookie(http.get("/__test/render")) != null, "render keeps the flash and the token in a session");
        assertTrue(sessionCookie(http.get("/health")) == null);
    }
}
