package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.service.PasswordHasher;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * FR-AUT-11, SDD 10.2 routes 23 and 24, SDD 12.10: the administrator lists the accounts and enables or disables one.
 * The service refuses to disable the acting administrator and the last enabled administrator; a disabled account loses its
 * session at its next request; the list shows no hash. Only {@code enable} and {@code disable} exist ({@code reset} is COULD
 * and is not built, so it is a 400).
 */
class UserAdminWebTest extends WebTestBase {

    private static final String SECOND_ADMIN = "admin2";
    private static final String SECOND_PASSWORD = "Second-Admin-Pass-1!";

    private HttpTestClient admin;

    @BeforeAll
    void secondAdmin() throws Exception {
        String hash = new PasswordHasher(app.services().config().pbkdf2Iterations()).hash(SECOND_PASSWORD.toCharArray());
        execute("INSERT INTO staff_users (username, password_hash, user_role, enabled, failed_attempts, session_version, created_at)"
                + " VALUES (?, ?, 'ADMIN', 1, 0, 0, NOW(3))", SECOND_ADMIN, hash);
        execute("INSERT INTO staff_users (username, password_hash, user_role, enabled, failed_attempts, session_version, created_at)"
                + " VALUES (?, ?, 'KITCHEN_STAFF', 1, 0, 0, NOW(3))", "<b>cook</b>", hash);
    }

    @BeforeEach
    void freshState() throws Exception {
        execute("UPDATE staff_users SET enabled = 1, failed_attempts = 0, locked_until = NULL");
        admin = app.adminBrowser();
    }

    @AfterEach
    void everybodyEnabled() throws Exception {
        execute("UPDATE staff_users SET enabled = 1");
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private long idOf(String username) throws Exception {
        return Long.parseLong(text("SELECT id FROM staff_users WHERE username = ?", username));
    }

    private Reply post(HttpTestClient browser, Map<String, String> fields) {
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("_csrf", TestApp.tokenOf(browser));
        return browser.postForm("/admin/users", form);
    }

    private String after(HttpTestClient browser, Reply reply) {
        HttpTestClient.assertRedirect(reply, "/admin/users");
        assertEquals("", reply.body());
        return plain(browser.get("/admin/users").body());
    }

    private boolean enabled(String username) throws Exception {
        return "1".equals(text("SELECT enabled FROM staff_users WHERE username = ?", username));
    }

    @Test
    void theListShowsEveryAccountWithRoleAndStateAndNeverAHash() throws Exception {
        Reply page = admin.get("/admin/users");
        assertEquals(200, page.status());
        String text = plain(page.body());
        assertTrue(text.contains(app.adminUsername()) && text.contains(app.staffUsername()) && text.contains(SECOND_ADMIN));
        assertTrue(text.contains("ADMIN") && text.contains("KITCHEN_STAFF") && text.contains("enabled"));
        assertTrue(page.body().contains("name=\"action\" value=\"disable\"") && page.body().contains("name=\"_csrf\""));
        assertFalse(page.body().contains(text("SELECT password_hash FROM staff_users WHERE id = ?", idOf(SECOND_ADMIN))), "no hash");
        assertFalse(page.body().toLowerCase().contains("pbkdf2"));
        assertFalse(page.body().contains("<b>cook</b>"), "a user name is escaped");
        assertTrue(page.body().contains("&lt;b&gt;cook&lt;/b&gt;"));
    }

    @Test
    void disablingAStaffAccountEndsItsSessionAtItsNextRequestAndBlocksANewLogin() throws Exception {
        HttpTestClient staff = app.staffBrowser();
        assertEquals(200, staff.get("/inventory").status());
        String text = after(admin, post(admin, Map.of("action", "disable", "userId", Long.toString(idOf(app.staffUsername())))));
        assertTrue(text.contains("disabled"), text);
        assertFalse(enabled(app.staffUsername()));
        assertTrue(plain(admin.get("/admin/users").body()).contains("disabled"));
        HttpTestClient.assertRedirect(staff.get("/inventory"), "/login");

        HttpTestClient again = app.browser();
        String token = HttpTestClient.extractCsrf(again.get("/login").body());
        HttpTestClient.assertRedirect(again.postForm("/login", Map.of("username", app.staffUsername(), "password", app.staffPassword(), "_csrf", token)), "/login");
    }

    @Test
    void enablingAgainAllowsANewLoginButNotTheOldSession() throws Exception {
        HttpTestClient old = app.staffBrowser();
        long id = idOf(app.staffUsername());
        after(admin, post(admin, Map.of("action", "disable", "userId", Long.toString(id))));
        String text = after(admin, post(admin, Map.of("action", "enable", "userId", Long.toString(id))));
        assertTrue(text.contains("enabled"), text);
        assertTrue(enabled(app.staffUsername()));
        HttpTestClient.assertRedirect(old.get("/inventory"), "/login");
        assertEquals(200, app.staffBrowser().get("/inventory").status());
    }

    @Test
    void anAdministratorCannotDisableTheirOwnAccount() throws Exception {
        String text = after(admin, post(admin, Map.of("action", "disable", "userId", Long.toString(idOf(app.adminUsername())))));
        assertTrue(text.contains("cannot disable your own account"), text);
        assertTrue(enabled(app.adminUsername()));
        assertEquals(200, admin.get("/admin/users").status(), "and they are still signed in");
    }

    @Test
    void anotherAdministratorMayBeDisabledWhileASecondOneRemains() throws Exception {
        String text = after(admin, post(admin, Map.of("action", "disable", "userId", Long.toString(idOf(SECOND_ADMIN)))));
        assertTrue(text.contains("disabled"), text);
        assertFalse(enabled(SECOND_ADMIN));
        assertTrue(enabled(app.adminUsername()));
    }

    @Test
    void anUnknownAccountIsAFlashMessageAndABadRequestIsA400() throws Exception {
        assertTrue(after(admin, post(admin, Map.of("action", "disable", "userId", "999999"))).contains("No such account"));
        assertTrue(after(admin, post(admin, Map.of("action", "enable", "userId", "999999"))).contains("No such account"));
        assertEquals(400, post(admin, Map.of("action", "disable", "userId", "abc")).status());
        assertEquals(400, post(admin, Map.of("action", "disable")).status());
        assertEquals(400, post(admin, Map.of("action", "reset", "userId", Long.toString(idOf(app.staffUsername())), "newPassword", "Whatever-Pass-1!")).status(), "reset is COULD and not built");
        assertEquals(400, post(admin, Map.of("userId", "1")).status());
        assertEquals(400, post(admin, Map.of("action", "delete", "userId", "1")).status());
        assertTrue(enabled(app.staffUsername()));
    }

    @Test
    void staffVisitorsAndRequestsWithoutATokenChangeNothing() throws Exception {
        HttpTestClient staff = app.staffBrowser();
        long target = idOf(SECOND_ADMIN);
        assertEquals(403, staff.get("/admin/users").status());
        assertEquals(403, post(staff, Map.of("action", "disable", "userId", Long.toString(target))).status());
        HttpTestClient.assertRedirect(app.browser().get("/admin/users"), "/login");
        assertEquals(403, admin.postForm("/admin/users", Map.of("action", "disable", "userId", Long.toString(target))).status());
        assertEquals(403, admin.postForm("/admin/users", Map.of("action", "disable", "userId", Long.toString(target), "_csrf", "forged")).status());
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/admin/users", Map.of("action", "disable", "userId", Long.toString(target), "_csrf", token)), "/login");
        assertTrue(enabled(SECOND_ADMIN));
    }

    /** Two administrators disable each other at the same moment: the guard on the last enabled administrator must hold. */
    @RepeatedTest(20)
    void twoAdministratorsDisablingEachOtherNeverLeaveTheSystemWithoutAnAdministrator() throws Exception {
        HttpTestClient first = app.adminBrowser();
        HttpTestClient second = app.loginAs(SECOND_ADMIN, SECOND_PASSWORD);
        String firstToken = TestApp.tokenOf(first);
        String secondToken = TestApp.tokenOf(second);
        long firstId = idOf(app.adminUsername());
        long secondId = idOf(SECOND_ADMIN);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> a = pool.submit(() -> {
                go.await();
                return first.postForm("/admin/users", Map.of("action", "disable", "userId", Long.toString(secondId), "_csrf", firstToken));
            });
            Future<Reply> b = pool.submit(() -> {
                go.await();
                return second.postForm("/admin/users", Map.of("action", "disable", "userId", Long.toString(firstId), "_csrf", secondToken));
            });
            go.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM staff_users WHERE user_role = 'ADMIN' AND enabled = 1"),
                "exactly one of the two disabled the other, and the survivor is still an enabled administrator");
    }
}
