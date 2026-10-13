package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FR-WEB-06: the dashboard shows what the backend holds, refreshes itself every five seconds and is protected like every page. */
class DashboardWebTest extends WebTestBase {

    @BeforeEach
    void cleanSlate() throws Exception {
        execute("DELETE FROM order_status_history");
        execute("DELETE FROM order_items");
        execute("DELETE FROM orders");
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
    }

    private static Map<OrderStatus, Long> countsOf(String html) {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        Matcher table = Pattern.compile("(?s)<table id=\"status-counts\">(.*?)</table>").matcher(html);
        assertTrue(table.find(), "status table: " + html);
        Matcher row = Pattern.compile("badge-[a-z_]+\">([A-Z_]+)</span>\\s*</td><td>(\\d+)</td>").matcher(table.group(1));
        while (row.find()) {
            counts.put(OrderStatus.valueOf(row.group(1)), Long.parseLong(row.group(2)));
        }
        return counts;
    }

    @Test
    void theDashboardAsksTheBrowserToReloadEveryFiveSecondsAndNeedsNoScript() {
        String body = app.staffBrowser().get("/dashboard").body();
        assertTrue(body.contains("<meta http-equiv=\"refresh\" content=\"5\">"), body);
        assertFalse(body.toLowerCase().contains("<script"));
        assertTrue(body.contains("<h1>Dashboard</h1>"));
    }

    @Test
    void theRefreshIsExactlyOneMetaTagInTheHeadAskingForTheFiveSecondsOfTheRequirement() {
        Reply page = app.staffBrowser().get("/dashboard");
        Matcher tags = Pattern.compile("<meta\\s+http-equiv=\"refresh\"\\s+content=\"([^\"]*)\"\\s*>").matcher(page.body());
        assertTrue(tags.find(), page.body());
        assertEquals("5", tags.group(1), "FR-WEB-06: every 5 seconds, no address, so the browser reloads this very page");
        assertFalse(tags.find(), "exactly one refresh instruction");
        int head = page.body().indexOf("</head>");
        assertTrue(page.body().indexOf("http-equiv=\"refresh\"") < head, "it is in the head");
        assertTrue(page.headers().allValues("Refresh").isEmpty(), "no Refresh header: the page carries its own instruction");
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("script-src 'none'") && !csp.contains("unsafe-inline"), "the refresh needs no script and no CSP relaxation: " + csp);
        assertFalse(page.body().toLowerCase().contains("<script"));
    }

    @Test
    void noOtherPageRefreshesItself() throws Exception {
        pauseAll();
        long id = submit(OrderSource.SWIGGY, "RF-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        HttpTestClient admin = app.adminBrowser();
        for (String path : new String[] {"/orders", "/orders?status=ROUTED", "/orders/view?id=" + id, "/inventory", "/login", "/static/missing.css"}) {
            assertFalse(admin.get(path).body().contains("http-equiv=\"refresh\""), path + " must not reload itself");
        }
        assertFalse(app.browser().get("/login").body().contains("http-equiv=\"refresh\""));
    }

    @Test
    void doingWhatTheBrowserDoesAfterTheRefreshShowsTheCurrentStateWithoutWaiting() throws Exception {
        pauseAll();
        HttpTestClient staff = app.staffBrowser();
        Reply first = staff.get("/dashboard");
        Matcher tag = Pattern.compile("content=\"(\\d+)\"").matcher(first.body());
        assertTrue(tag.find());
        assertEquals(5, Integer.parseInt(tag.group(1)));
        assertEquals(0L, countsOf(first.body()).get(OrderStatus.ROUTED) + countsOf(first.body()).get(OrderStatus.VALIDATED)
                + countsOf(first.body()).get(OrderStatus.PLACED));
        submit(OrderSource.SWIGGY, "RF-2", "BIRYANI", "Asha", "Chicken Biryani", 1);
        submit(OrderSource.SWIGGY, "RF-3", "BIRYANI", "Asha", "Chicken Biryani", 1);
        // a refresh without an address requests the same URL again, with the same session: that is all the browser does
        Reply second = staff.get("/dashboard");
        Map<OrderStatus, Long> after = countsOf(second.body());
        assertEquals(2L, after.get(OrderStatus.ROUTED) + after.get(OrderStatus.VALIDATED) + after.get(OrderStatus.PLACED), after.toString());
        assertTrue(second.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").contains("2 / "), "the queue size changed too");
    }

    @Test
    void theRecoveryBannerFollowsTheRecoveringStateOfTheDashboardService() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean recovering = new java.util.concurrent.atomic.AtomicBoolean();
        var original = app.useRecoveryFlag(recovering);
        try {
            HttpTestClient staff = app.staffBrowser();
            assertFalse(staff.get("/dashboard").body().contains("id=\"recovering\""), "no banner while the application is not recovering");
            recovering.set(true);
            String shown = staff.get("/dashboard").body();
            assertTrue(shown.contains("id=\"recovering\""), shown);
            assertTrue(shown.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").contains("Recovering: the application is re-queueing unfinished orders"), shown);
            assertTrue(shown.contains("role=\"status\""), "announced to assistive technology as a status message");
            assertTrue(shown.contains("http-equiv=\"refresh\""), "the page keeps refreshing, so the banner disappears by itself when recovery ends");
            recovering.set(false);
            assertFalse(staff.get("/dashboard").body().contains("id=\"recovering\""), "and it is gone again at the next refresh");
        } finally {
            app.restoreServices(original);
        }
    }

    @Test
    void theRealWiringReportsNoRecoveryOnceTheApplicationIsReady() throws Exception {
        assertTrue(app.appContext().awaitReady(60, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(app.appContext().recovery().isRecovering());
        assertFalse(app.staffBrowser().get("/dashboard").body().contains("id=\"recovering\""), "the production supplier is wired to the recovery service");
    }

    @Test
    void allFiveBlocksArePresentForStaffAndAdministratorAlike() {
        for (HttpTestClient browser : new HttpTestClient[] {app.staffBrowser(), app.adminBrowser()}) {
            Reply page = browser.get("/dashboard");
            assertEquals(200, page.status());
            for (String block : new String[] {"id=\"status-counts\"", "id=\"stations\"", "id=\"metrics\"", "id=\"low-stock\"", "id=\"simulator\""}) {
                assertTrue(page.body().contains(block), block);
            }
            assertTrue(page.body().contains("<progress"), "queue fill is shown as a bar and as text");
        }
    }

    @Test
    void theStatusCountsMatchTheDatabaseForEveryStatusIncludingZero() throws Exception {
        pauseAll();
        submit(OrderSource.SWIGGY, "DB-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        submit(OrderSource.SWIGGY, "DB-2", "BIRYANI", "Asha", "Chicken Biryani", 1);
        long cancelled = submit(OrderSource.ZOMATO, "DB-3", "BURGER", "Bhavna", "Cola", 1);
        execute("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled);
        Map<OrderStatus, Long> shown = countsOf(app.staffBrowser().get("/dashboard").body());
        Map<OrderStatus, Long> real = app.services().orderQueries().countByStatus();
        for (OrderStatus status : OrderStatus.values()) {
            assertEquals(real.getOrDefault(status, 0L), shown.get(status), "count of " + status);
        }
        assertEquals(1L, shown.get(OrderStatus.CANCELLED));
        assertEquals(0L, shown.get(OrderStatus.DELIVERED));
        assertEquals(9, shown.size(), "every lifecycle status has a row");
    }

    @Test
    void queueSizeAndCapacityWorkerStateGenerationAndRestartsMatchTheBackend() throws Exception {
        pauseAll();
        for (int i = 1; i <= 3; i++) {
            submit(OrderSource.SWIGGY, "Q-" + i, "BIRYANI", "Asha", "Chicken Biryani", 1);
        }
        String body = app.staffBrowser().get("/dashboard").body();
        for (StationView station : app.appContext().orchestrator().stationViews()) {
            Matcher row = Pattern.compile("(?s)<tr>\\s*<td>" + Pattern.quote(station.stationCode()) + " \\(.*?</tr>").matcher(body);
            assertTrue(row.find(), "row of " + station.stationCode());
            String text = row.group().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
            assertTrue(text.contains(station.queueSize() + " / " + station.queueCapacity()), text);
            assertTrue(text.contains("PAUSED") && text.contains("(paused)"), "worker state is text: " + text);
            assertTrue(text.contains(" " + station.worker().generation() + " " + station.worker().restarts() + " "), "generation and restarts: " + text);
        }
        assertTrue(body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").contains("3 / "), "three orders wait in the BIRYANI queue");
    }

    @Test
    void metricsShowTheCountersOfTheRunningApplication() throws Exception {
        pauseAll();
        submit(OrderSource.SWIGGY, "M-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        var metrics = app.appContext().metrics().view();
        String text = app.adminBrowser().get("/dashboard").body();
        Matcher table = Pattern.compile("(?s)<table id=\"metrics\".*?</table>").matcher(text);
        assertTrue(table.find());
        String plain = table.group().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        assertTrue(plain.contains("Submitted " + metrics.submitted()), plain);
        assertTrue(plain.contains("Processed " + metrics.processed()), plain);
        assertTrue(plain.contains("Failed " + metrics.failed()), plain);
        assertTrue(plain.contains("Rejected " + metrics.rejected()), plain);
        assertTrue(plain.contains("Average processing time (ms)"), plain);
    }

    @Test
    void anItemAtOrBelowItsReorderLevelIsFlaggedWithTheWordLowAndOthersAreNot() throws Exception {
        String quiet = app.staffBrowser().get("/dashboard").body();
        assertTrue(quiet.contains("No item is below its reorder level."), quiet);
        execute("UPDATE inventory SET stock_qty = 10, reorder_level = 10 WHERE item_id = ?", itemId("Cola"));
        execute("UPDATE inventory SET stock_qty = 3, reorder_level = 10 WHERE item_id = ?", itemId("Margherita"));
        String body = app.staffBrowser().get("/dashboard").body();
        Matcher table = Pattern.compile("(?s)<table id=\"low-stock\">.*?</table>").matcher(body);
        assertTrue(table.find());
        String low = table.group();
        assertTrue(low.contains("Cola") && low.contains("Margherita"), low);
        assertFalse(low.contains("Chicken Biryani"), "an item above its reorder level is not listed");
        assertEquals(2, low.split("<strong class=\"low\">LOW</strong>", -1).length - 1, "the flag is text, not colour alone");
    }

    @Test
    void theSimulatorAndCompensationBlocksShowTheirState() throws Exception {
        String body = app.staffBrowser().get("/dashboard").body();
        String plain = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        assertTrue(plain.contains("Simulator stopped"), plain);
        assertTrue(plain.contains("Orders awaiting compensation 0"), plain);
        assertFalse(body.contains("id=\"recovering\""), "no recovery banner once start-up recovery has finished");

        pauseAll();
        long id = submit(OrderSource.SWIGGY, "CP-1", "BIRYANI", "Asha", "Chicken Biryani", 1);
        execute("UPDATE orders SET status = 'VALIDATED', compensation_state = 'PENDING' WHERE id = ?", id);
        try {
            String after = app.staffBrowser().get("/dashboard").body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
            assertTrue(after.contains("Orders awaiting compensation 1"), after);
        } finally {
            execute("UPDATE orders SET compensation_state = NULL WHERE id = ?", id);
        }
    }

    @Test
    void thePageIsProtectedAndTheFiveSecondReloadOfADisabledUserIsRejected() throws Exception {
        HttpTestClient.assertRedirect(app.browser().get("/dashboard"), "/login");
        HttpTestClient staff = app.staffBrowser();
        assertEquals(200, staff.get("/dashboard").status());
        execute("UPDATE staff_users SET enabled = 0 WHERE username = ?", app.staffUsername());
        try {
            HttpTestClient.assertRedirect(staff.get("/dashboard"), "/login");
        } finally {
            execute("UPDATE staff_users SET enabled = 1 WHERE username = ?", app.staffUsername());
        }
    }
}
