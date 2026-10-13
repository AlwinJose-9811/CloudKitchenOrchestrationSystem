package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * FR-WEB-14, SDD 7.10.11 (OP-1..OP-8): the administrator's compensation page. It lists the orders awaiting compensation and
 * offers the one manual operation, Retry. The pending orders are real: submitted through the service, so that their stock
 * really is deducted, then left in the state the pipeline leaves an order it could neither queue nor compensate.
 */
class CompensationOperatorWebTest extends WebTestBase {

    private HttpTestClient admin;
    private HttpTestClient secondAdmin;
    private HttpTestClient staff;

    @BeforeEach
    void cleanSlate() throws Exception {
        execute("DELETE FROM order_status_history");
        execute("DELETE FROM order_items");
        execute("DELETE FROM orders");
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        if (admin == null) {
            admin = app.adminBrowser();
            secondAdmin = app.adminBrowser();
            staff = app.staffBrowser();
        }
        // drain flash messages left by an earlier test, so that a test only ever sees its own
        admin.get("/admin/compensations");
        secondAdmin.get("/admin/compensations");
        staff.get("/orders");
        pauseAll();
    }

    private Reply retry(HttpTestClient browser, long orderId) {
        return browser.postForm("/admin/compensations", Map.of("action", "retry", "orderId", Long.toString(orderId), "_csrf", TestApp.tokenOf(browser)));
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private String dashboardCount() {
        String text = plain(staff.get("/dashboard").body());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Orders awaiting compensation (\\d+)").matcher(text);
        assertTrue(m.find(), text);
        return m.group(1);
    }

    @Test
    void withNothingPendingThePageSaysSoAndMatchesTheDashboard() {
        Reply page = admin.get("/admin/compensations");
        assertEquals(200, page.status());
        String text = plain(page.body());
        assertTrue(text.contains("0 awaiting compensation."), text);
        assertTrue(text.contains("No order is awaiting compensation."), text);
        assertEquals("0", dashboardCount());
    }

    @Test
    void theListShowsOrderBrandReasonAttemptsAgeAndNextAttemptAndMatchesTheDashboardCount() throws Exception {
        long first = pendingOrder("CP-1", "BIRYANI", "Chicken Biryani", 2);
        long second = pendingOrder("CP-2", "BURGER", "Cola", 1);
        execute("UPDATE orders SET compensation_attempts = 4, compensation_reason = 'STATION_UNAVAILABLE', compensation_next_at = NULL WHERE id = ?", second);
        Reply page = admin.get("/admin/compensations");
        String text = plain(page.body());
        assertTrue(text.contains("2 awaiting compensation."), text);
        assertTrue(text.matches(".*\\b" + first + " BIRYANI QUEUE_FULL 1 \\d+ \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d UTC Retry compensation.*"), text);
        assertTrue(text.matches(".*\\b" + second + " BURGER STATION_UNAVAILABLE 4 \\d+ as soon as possible Retry compensation.*"), text);
        assertTrue(page.body().indexOf(">" + first + "</a>") < page.body().indexOf(">" + second + "</a>"), "oldest id first");
        assertTrue(page.body().contains("href=\"/orders/view?id=" + first + "\""));
        assertEquals("2", dashboardCount(), "the page and the dashboard count the same orders");
    }

    @Test
    void theAdministratorsRetryFailsTheOrderRestoresItsStockOnceAndIsAudited() throws Exception {
        long id = pendingOrder("CP-3", "BIRYANI", "Chicken Biryani", 3);
        assertEquals(97, stockOf("Chicken Biryani"));
        Reply post = retry(admin, id);
        HttpTestClient.assertRedirect(post, "/admin/compensations");
        assertEquals("", post.body());
        String text = plain(admin.get("/admin/compensations").body());
        assertTrue(text.contains("Compensation of order " + id + " is complete: the order has failed and its stock has been given back."), text);
        assertTrue(text.contains("No order is awaiting compensation."), text);
        assertEquals("FAILED", statusOf(id));
        assertEquals(100, stockOf("Chicken Biryani"), "the three portions are back");
        assertEquals("admin:" + app.adminUsername(), text("SELECT actor FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
        assertEquals("COMPENSATION_MANUAL", text("SELECT note FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
        assertEquals("0", dashboardCount());
        String detail = admin.get("/orders/view?id=" + id).body();
        assertFalse(detail.contains("id=\"compensation-banner\""), "the banner is gone");
        assertTrue(detail.contains("badge-failed"));
    }

    @Test
    void aSecondRetryIsAnAlreadyResolvedMessageAndNeverRestoresTwice() throws Exception {
        long id = pendingOrder("CP-4", "BIRYANI", "Chicken Biryani", 2);
        retry(admin, id);
        assertEquals(100, stockOf("Chicken Biryani"));
        HttpTestClient.assertRedirect(retry(admin, id), "/admin/compensations");
        String text = plain(admin.get("/admin/compensations").body());
        assertTrue(text.contains("Order " + id + " is already resolved; nothing was changed."), text);
        assertEquals(100, stockOf("Chicken Biryani"), "OP-5: restored exactly once");
        assertEquals(1, count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
    }

    @Test
    void anOrderThatIsNotPendingOrDoesNotExistIsReportedAsAlreadyResolved() throws Exception {
        long ordinary = submit(com.cloudkitchen.domain.enums.OrderSource.SWIGGY, "CP-5", "BIRYANI", "Asha", "Chicken Biryani", 1);
        for (long id : new long[] {ordinary, 987654321L}) {
            HttpTestClient.assertRedirect(retry(admin, id), "/admin/compensations");
            assertTrue(plain(admin.get("/admin/compensations").body()).contains("Order " + id + " is already resolved; nothing was changed."));
        }
        assertEquals(99, stockOf("Chicken Biryani"), "an ordinary order is untouched");
        assertTrue(!"FAILED".equals(statusOf(ordinary)));
    }

    @Test
    void whenTheAttemptFailsTheOrderStaysPendingTheAttemptIsRecordedAndALaterRetrySucceeds() throws Exception {
        long id = pendingOrder("CP-6", "BIRYANI", "Chicken Biryani", 2);
        int stock = stockOf("Chicken Biryani");
        execute("RENAME TABLE inventory TO inventory_hidden");
        try {
            HttpTestClient.assertRedirect(retry(admin, id), "/admin/compensations");
        } finally {
            execute("RENAME TABLE inventory_hidden TO inventory");
        }
        String text = plain(admin.get("/admin/compensations").body());
        assertTrue(text.contains("The compensation of order " + id + " could not be completed this time."), text);
        assertFalse(text.contains("Exception") || text.contains("SQL"), "no detail leaks into the message");
        assertEquals("VALIDATED", statusOf(id));
        assertEquals("PENDING", text("SELECT compensation_state FROM orders WHERE id = ?", id));
        assertEquals("2", text("SELECT compensation_attempts FROM orders WHERE id = ?", id), "the failed attempt is counted");
        assertEquals(stock, stockOf("Chicken Biryani"), "a rolled-back attempt changes no stock");
        assertEquals(0, count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));

        retry(admin, id);
        assertEquals("FAILED", statusOf(id));
        assertEquals(stock + 2, stockOf("Chicken Biryani"));
    }

    @Test
    void kitchenStaffAndAnonymousCallersCannotUseThePageOrTheOperation() throws Exception {
        long id = pendingOrder("CP-7", "BIRYANI", "Chicken Biryani", 1);
        assertEquals(403, staff.get("/admin/compensations").status());
        assertEquals(403, retry(staff, id).status());
        HttpTestClient.assertRedirect(app.browser().get("/admin/compensations"), "/login");
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/admin/compensations", Map.of("action", "retry", "orderId", Long.toString(id), "_csrf", token)), "/login");
        assertEquals("VALIDATED", statusOf(id));
        assertEquals("PENDING", text("SELECT compensation_state FROM orders WHERE id = ?", id));
    }

    @Test
    void theServiceItselfRefusesANonAdministratorEvenIfTheFilterWereMissing() throws Exception {
        long id = pendingOrder("CP-8", "BIRYANI", "Chicken Biryani", 1);
        com.cloudkitchen.dto.SessionUser cook = app.services().auth().authenticate(app.staffUsername(), app.staffPassword().toCharArray());
        com.cloudkitchen.exception.CloudKitchenException e = org.junit.jupiter.api.Assertions.assertThrows(com.cloudkitchen.exception.CloudKitchenException.class,
                () -> app.services().compensation().retry(id, cook));
        assertEquals("FORBIDDEN", e.getCode());
        assertEquals("VALIDATED", statusOf(id));
    }

    @Test
    void withoutAValidTokenNothingHappens() throws Exception {
        long id = pendingOrder("CP-9", "BIRYANI", "Chicken Biryani", 1);
        assertEquals(403, admin.postForm("/admin/compensations", Map.of("action", "retry", "orderId", Long.toString(id))).status());
        assertEquals(403, admin.postForm("/admin/compensations", Map.of("action", "retry", "orderId", Long.toString(id), "_csrf", "forged")).status());
        assertEquals("VALIDATED", statusOf(id));
    }

    @Test
    void badInputIsABadRequestAndChangesNothing() throws Exception {
        long id = pendingOrder("CP-10", "BIRYANI", "Chicken Biryani", 1);
        String token = TestApp.tokenOf(admin);
        for (Map<String, String> form : List.of(Map.of("orderId", Long.toString(id)), Map.of("action", "clear", "orderId", Long.toString(id)),
                Map.of("action", "retry"), Map.of("action", "retry", "orderId", "abc"), Map.of("action", "resolve", "orderId", Long.toString(id)))) {
            java.util.Map<String, String> withToken = new java.util.LinkedHashMap<>(form);
            withToken.put("_csrf", token);
            Reply reply = admin.postForm("/admin/compensations", withToken);
            assertEquals(400, reply.status(), form.toString());
            assertFalse(reply.body().contains("Exception"));
        }
        assertEquals("PENDING", text("SELECT compensation_state FROM orders WHERE id = ?", id), "there is no operation that clears the marker (OP-7)");
    }

    @Test
    void thereIsNoWayToResolveAPendingOrderExceptTheGuardedRetry() throws Exception {
        long id = pendingOrder("CP-11", "BIRYANI", "Chicken Biryani", 1);
        String page = admin.get("/admin/compensations").body();
        java.util.regex.Matcher actions = java.util.regex.Pattern.compile("name=\"action\" value=\"([a-z]+)\"").matcher(page);
        List<String> offered = new ArrayList<>();
        while (actions.find()) {
            offered.add(actions.group(1));
        }
        assertEquals(List.of("retry"), offered, "the only operation on the page is Retry");
        assertTrue(admin.get("/orders/view?id=" + id).body().contains("id=\"compensation-banner\""));
        assertFalse(admin.get("/orders/view?id=" + id).body().contains("id=\"cancel-form\""));
    }

    /** Two administrators press Retry on the same order at the same moment: one resolves it, the other is told it is resolved, stock +n once. */
    @RepeatedTest(20)
    void twoSimultaneousRetriesResolveTheOrderOnceAndRestoreItsStockOnce() throws Exception {
        long id = pendingOrder("CR-" + System.nanoTime(), "BIRYANI", "Chicken Biryani", 2);
        assertEquals(98, stockOf("Chicken Biryani"));
        HttpTestClient[] people = {admin, secondAdmin};
        String[] tokens = {TestApp.tokenOf(admin), TestApp.tokenOf(secondAdmin)};
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "retry-test"));
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                HttpTestClient person = people[i];
                String token = tokens[i];
                replies.add(pool.submit(() -> {
                    go.await();
                    return person.postForm("/admin/compensations", Map.of("action", "retry", "orderId", Long.toString(id), "_csrf", token));
                }));
            }
            go.countDown();
            for (Future<Reply> reply : replies) {
                HttpTestClient.assertRedirect(reply.get(60, TimeUnit.SECONDS), "/admin/compensations");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("FAILED", statusOf(id));
        assertEquals(100, stockOf("Chicken Biryani"), "OP-5: the loser changed nothing");
        assertEquals(1, count("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'FAILED'", id));
        int resolved = 0;
        for (HttpTestClient person : people) {
            String text = plain(person.get("/admin/compensations").body());
            if (text.contains("is complete: the order has failed")) {
                resolved++;
            } else {
                assertTrue(text.contains("is already resolved") || text.contains("could not be completed this time"), text);
            }
        }
        assertEquals(1, resolved, "exactly one administrator was told that this call resolved it");
    }
}
