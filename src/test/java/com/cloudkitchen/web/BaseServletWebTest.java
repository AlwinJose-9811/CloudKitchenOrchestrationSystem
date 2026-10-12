package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.time.Clock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The controller base class, the layout and the wiring of the services (checkpoint C1), through the real application. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaseServletWebTest {

    private DbFixture db;
    private TestApp app;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(Clock.systemUTC());
        app = TestApp.start(db);
    }

    @AfterAll
    void stop() {
        if (app != null) {
            app.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void parametersAreParsedWithDefaultsAndCaseInsensitiveEnums() {
        HttpTestClient http = app.browser();
        assertEquals("n=7 id=0 kind=null", http.get("/__test/params").body());
        assertEquals("n=12 id=99 kind=A", http.get("/__test/params?n=12&id=99&kind=a").body());
        assertEquals("n=7 id=0 kind=null", http.get("/__test/params?n=&kind=").body(), "empty values count as absent for optional parameters");
        assertEquals(400, http.get("/__test/params?n=1.5").status());
        assertEquals(400, http.get("/__test/params?id=").status(), "an empty required long is a bad request");
    }

    @Test
    void aRedirectIsPrgAndTheFlashMessageIsShownExactlyOnceAndEscaped() {
        HttpTestClient browser = app.browser();
        Reply post = browser.get("/__test/flash-add?text=" + java.net.URLEncoder.encode("<b>done</b> & \"quoted\"", java.nio.charset.StandardCharsets.UTF_8));
        HttpTestClient.assertRedirect(post, "/__test/render");
        Reply first = browser.get("/__test/render");
        assertEquals(200, first.status());
        assertTrue(first.body().contains("&lt;b&gt;done&lt;/b&gt; &amp; &#034;quoted&#034;") || first.body().contains("&lt;b&gt;done&lt;/b&gt; &amp; &quot;quoted&quot;"),
                first.body());
        assertFalse(first.body().contains("<b>done</b>"), "never unescaped");
        assertTrue(first.body().contains("class=\"flash flash-success\""));
        assertTrue(first.body().contains("class=\"flash flash-error\""));
        assertTrue(first.body().contains("Second message"));
        Reply second = browser.get("/__test/render");
        assertFalse(second.body().contains("done"), "a flash message appears once");
        assertFalse(second.body().contains("Second message"));
    }

    @Test
    void aFlashMessageBelongsToTheSessionThatCreatedIt() {
        HttpTestClient one = app.browser();
        HttpTestClient other = app.browser();
        one.get("/__test/flash-add?text=private");
        other.get("/__test/session");
        assertFalse(other.get("/__test/render").body().contains("private"));
        assertTrue(one.get("/__test/render").body().contains("private"));
    }

    @Test
    void theLayoutShowsNoNavigationWithoutAUserAndTheRoleDependentNavigationWithOne() {
        HttpTestClient http = app.browser();
        String anonymous = http.get("/__test/render").body();
        assertFalse(anonymous.contains("Log out"), anonymous);
        assertFalse(anonymous.contains("/dashboard"));

        String staff = http.get("/__test/render?role=KITCHEN_STAFF&name=cook").body();
        assertTrue(staff.contains("href=\"/dashboard\"") && staff.contains("href=\"/orders\"") && staff.contains("href=\"/inventory\""), staff);
        assertFalse(staff.contains("/admin/"), "staff see no admin links");
        assertTrue(staff.contains("action=\"/logout\"") && staff.contains("Log out"));

        String admin = http.get("/__test/render?role=ADMIN&name=boss").body();
        assertTrue(admin.contains("href=\"/admin/simulator\"") && admin.contains("href=\"/admin/compensations\""), admin);
    }

    @Test
    void theUserNameInTheHeaderIsEscaped() {
        String body = app.browser().get("/__test/render?role=ADMIN&name=" + java.net.URLEncoder.encode("<img src=x onerror=alert(1)>", java.nio.charset.StandardCharsets.UTF_8)).body();
        assertFalse(body.contains("<img"), body);
        assertTrue(body.contains("&lt;img src=x onerror=alert(1)&gt;"), body);
    }

    @Test
    void theCsrfTagRendersTheSessionTokenAndEachBrowserHasItsOwn() {
        HttpTestClient one = app.browser();
        HttpTestClient other = app.browser();
        String a1 = HttpTestClient.extractCsrf(one.get("/__test/render?role=ADMIN").body());
        String a2 = HttpTestClient.extractCsrf(one.get("/__test/render?role=ADMIN").body());
        String b = HttpTestClient.extractCsrf(other.get("/__test/render?role=ADMIN").body());
        assertNotNull(a1);
        assertTrue(a1.matches("[A-Za-z0-9_-]{43}"), a1);
        assertEquals(a1, a2, "the token is stable within a session");
        assertNotEquals(a1, b, "and different between sessions");
    }

    @Test
    void theApplicationServicesAreHandedToTheWebLayerAndAuthenticateTheSeededAccounts() throws Exception {
        assertNotNull(app.services());
        assertNotNull(app.appContext());
        SessionUser admin = app.services().auth().authenticate(app.adminUsername(), app.adminPassword().toCharArray());
        assertEquals(Role.ADMIN, admin.role());
        SessionUser staff = app.services().auth().authenticate(app.staffUsername(), app.staffPassword().toCharArray());
        assertEquals(Role.KITCHEN_STAFF, staff.role());
        assertTrue(app.services().auth().validateSession(admin).isPresent(), "the per-request check accepts a fresh session");
        org.junit.jupiter.api.Assertions.assertThrows(AuthenticationException.class,
                () -> app.services().auth().authenticate(app.staffUsername(), "wrong-password".toCharArray()));
    }

    @Test
    void theServicesAreTheOnesOfTheRunningApplicationContext() throws Exception {
        assertTrue(app.services().orders() == app.appContext().orders());
        assertTrue(app.services().dashboard() == app.appContext().dashboard());
        assertTrue(app.services().auth() == app.appContext().auth());
        assertTrue(app.appContext().awaitReady(60, java.util.concurrent.TimeUnit.SECONDS), "start-up recovery finished");
        assertTrue(app.appContext().orders().isAccepting(), "the application is serving");
    }
}
