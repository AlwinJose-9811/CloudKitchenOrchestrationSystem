package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * FR-WEB-02, FR-LIF-05 (UI), RR-7, SDD 10.2 route 10: cancelling an order from the order page. Success and every refusal
 * end in a redirect with one flash message that is shown once; the stock is given back exactly once; the lifecycle decides
 * what can be cancelled; the CSRF, login and role checks stand in front. Workers are paused so that the orders under test
 * stay where the test put them.
 */
class OrderPrgWebTest extends WebTestBase {

    private HttpTestClient staff;
    private HttpTestClient admin;

    @BeforeEach
    void freshStockAndSessions() throws Exception {
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        if (staff == null) {
            staff = app.staffBrowser();
            admin = app.adminBrowser();
        }
        // drain flash messages left by an earlier test, so that a test only ever sees its own
        staff.get("/orders");
        admin.get("/orders");
        pauseAll();
    }

    private Reply cancel(HttpTestClient browser, long id) {
        return browser.postForm("/orders/cancel", Map.of("id", Long.toString(id), "_csrf", TestApp.tokenOf(browser)));
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    @Test
    void cancellingRedirectsToTheOrderPageShowsOneSuccessMessageOnceAndDoesNotResubmitOnRefresh() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PG-1", "BIRYANI", "Asha", "Chicken Biryani", 2);
        assertEquals(98, stockOf("Chicken Biryani"));
        Reply post = cancel(staff, id);
        HttpTestClient.assertRedirect(post, "/orders/view?id=" + id);
        assertEquals("", post.body(), "a POST never renders a page itself");

        Reply page = staff.get("/orders/view?id=" + id);
        assertEquals(200, page.status());
        assertTrue(plain(page.body()).contains("Order " + id + " has been cancelled."), page.body());
        assertTrue(page.body().contains("flash-success"));
        assertTrue(page.body().contains("badge-cancelled"));
        assertFalse(page.body().contains("id=\"cancel-form\""), "a cancelled order offers no Cancel button");
        assertFalse(plain(staff.get("/orders/view?id=" + id).body()).contains("has been cancelled"), "the flash message is shown once");
        assertEquals("CANCELLED", statusOf(id));
        assertEquals(100, stockOf("Chicken Biryani"), "the stock is given back");
        assertEquals("user:" + app.staffUsername(), text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'", id));
    }

    @Test
    void anAdministratorCanCancelToo() throws Exception {
        long id = submit(OrderSource.ZOMATO, "PG-2", "BURGER", "Bhavna", "Cola", 1);
        HttpTestClient.assertRedirect(cancel(admin, id), "/orders/view?id=" + id);
        assertEquals("CANCELLED", statusOf(id));
        assertEquals("user:" + app.adminUsername(), text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'", id));
    }

    @Test
    void aSecondCancelOfTheSameOrderIsRefusedWithAMessageAndTheStockIsNotGivenBackAgain() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PG-3", "BIRYANI", "Asha", "Chicken Biryani", 3);
        cancel(staff, id);
        assertEquals(100, stockOf("Chicken Biryani"));
        Reply again = cancel(staff, id);
        HttpTestClient.assertRedirect(again, "/orders/view?id=" + id);
        String page = plain(staff.get("/orders/view?id=" + id).body());
        assertTrue(page.contains("Order " + id + " can no longer be cancelled."), page);
        assertEquals(100, stockOf("Chicken Biryani"), "restored exactly once");
        assertEquals(1, count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'", id));
    }

    @Test
    void theLifecycleDecidesStatusByStatusAndARefusedCancelChangesNothing() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PG-4", "BIRYANI", "Asha", "Chicken Biryani", 1);
        for (OrderStatus status : OrderStatus.values()) {
            forceStatus(id, status.name(), null);
            int stockBefore = stockOf("Chicken Biryani");
            Reply reply = cancel(staff, id);
            HttpTestClient.assertRedirect(reply, "/orders/view?id=" + id);
            String page = plain(staff.get("/orders/view?id=" + id).body());
            if (status.canTransitionTo(OrderStatus.CANCELLED)) {
                assertEquals("CANCELLED", statusOf(id), "cancel from " + status);
                assertTrue(page.contains("has been cancelled"), status + ": " + page);
            } else {
                assertEquals(status.name(), statusOf(id), "cancel from " + status + " is refused");
                assertTrue(page.contains("can no longer be cancelled"), status + ": " + page);
                assertEquals(stockBefore, stockOf("Chicken Biryani"), "a refused cancel gives no stock back (" + status + ")");
            }
        }
    }

    @Test
    void anOrderAwaitingCompensationCannotBeCancelledHereAndStaysPending() throws Exception {
        long id = pendingOrder("PG-5", "BIRYANI", "Chicken Biryani", 2);
        int stock = stockOf("Chicken Biryani");
        HttpTestClient.assertRedirect(cancel(admin, id), "/orders/view?id=" + id);
        String page = plain(admin.get("/orders/view?id=" + id).body());
        assertTrue(page.contains("awaiting compensation and cannot be cancelled here"), page);
        assertEquals("VALIDATED", statusOf(id));
        assertEquals("PENDING", text("SELECT compensation_state FROM orders WHERE id = ?", id));
        assertEquals(stock, stockOf("Chicken Biryani"), "OP-7: nothing gives stock back except the guarded compensation");
    }

    @Test
    void anUnknownOrderGoesBackToTheListWithAMessageAndABadIdIsA400() throws Exception {
        HttpTestClient.assertRedirect(cancel(staff, 987654321L), "/orders");
        assertTrue(plain(staff.get("/orders").body()).contains("There is no order 987654321."));
        String token = TestApp.tokenOf(staff);
        for (Map<String, String> form : List.of(Map.of("_csrf", token), Map.of("_csrf", token, "id", ""), Map.of("_csrf", token, "id", "abc"),
                Map.of("_csrf", token, "id", "1.5"), Map.of("_csrf", token, "id", "99999999999999999999"))) {
            Reply reply = staff.postForm("/orders/cancel", form);
            assertEquals(400, reply.status(), form.toString());
            assertFalse(reply.body().contains("Exception"));
        }
    }

    @Test
    void withoutAValidCsrfTokenNothingHappens() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PG-6", "BIRYANI", "Asha", "Chicken Biryani", 1);
        assertEquals(403, staff.postForm("/orders/cancel", Map.of("id", Long.toString(id))).status());
        assertEquals(403, staff.postForm("/orders/cancel", Map.of("id", Long.toString(id), "_csrf", "forged")).status());
        assertEquals(403, staff.postForm("/orders/cancel", Map.of("id", Long.toString(id), "_csrf", TestApp.tokenOf(admin))).status(), "another session's token");
        assertEquals(403, admin.send("POST", "/orders/cancel?id=" + id, Map.of("X-CSRF-Token", TestApp.tokenOf(admin)), "").status());
        assertNotEquals("CANCELLED", statusOf(id));
        assertEquals(99, stockOf("Chicken Biryani"));
    }

    @Test
    void anAnonymousCallerWithAValidTokenIsSentToTheLoginPageAndNothingHappens() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PG-7", "BIRYANI", "Asha", "Chicken Biryani", 1);
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/orders/cancel", Map.of("id", Long.toString(id), "_csrf", token)), "/login");
        assertNotEquals("CANCELLED", statusOf(id));
    }

    @Test
    void aDisabledUsersOldSessionCannotCancel() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PG-8", "BIRYANI", "Asha", "Chicken Biryani", 1);
        HttpTestClient doomed = app.staffBrowser();
        String token = TestApp.tokenOf(doomed);
        execute("UPDATE staff_users SET enabled = 0 WHERE username = ?", app.staffUsername());
        try {
            HttpTestClient.assertRedirect(doomed.postForm("/orders/cancel", Map.of("id", Long.toString(id), "_csrf", token)), "/login");
            assertNotEquals("CANCELLED", statusOf(id));
        } finally {
            execute("UPDATE staff_users SET enabled = 1 WHERE username = ?", app.staffUsername());
        }
    }

    @Test
    void cancelIsAPostOnlyRoute() throws Exception {
        assertEquals(403, staff.get("/orders/cancel?id=1").status(), "GET /orders/cancel is not a route: protected and ADMIN-only");
        assertEquals(405, admin.get("/orders/cancel?id=1").status());
    }

    /** Two people press Cancel on the same order at the same moment: one wins, the other is told, the stock comes back once. */
    @RepeatedTest(20)
    void twoSimultaneousCancelsCancelOnceAndGiveTheStockBackOnce() throws Exception {
        long id = submit(OrderSource.SWIGGY, "PC-" + System.nanoTime(), "BIRYANI", "Asha", "Chicken Biryani", 2);
        assertEquals(98, stockOf("Chicken Biryani"));
        HttpTestClient[] people = {staff, admin};
        String[] tokens = {TestApp.tokenOf(staff), TestApp.tokenOf(admin)};
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "cancel-test"));
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                HttpTestClient person = people[i];
                String token = tokens[i];
                replies.add(pool.submit(() -> {
                    go.await();
                    return person.postForm("/orders/cancel", Map.of("id", Long.toString(id), "_csrf", token));
                }));
            }
            go.countDown();
            for (Future<Reply> reply : replies) {
                HttpTestClient.assertRedirect(reply.get(60, TimeUnit.SECONDS), "/orders/view?id=" + id);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("CANCELLED", statusOf(id));
        assertEquals(100, stockOf("Chicken Biryani"), "restored exactly once");
        assertEquals(1, count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'", id));
        int successes = 0;
        for (HttpTestClient person : people) {
            String page = plain(person.get("/orders/view?id=" + id).body());
            if (page.contains("has been cancelled")) {
                successes++;
            } else {
                assertTrue(page.contains("can no longer be cancelled") || page.contains("changed by someone else"), page);
            }
        }
        assertEquals(1, successes, "exactly one of the two was told that it cancelled the order");
    }
}
