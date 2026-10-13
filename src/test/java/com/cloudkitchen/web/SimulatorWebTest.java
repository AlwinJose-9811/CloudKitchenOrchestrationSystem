package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.TestApp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-WEB-09: the administrator starts, stops and re-rates the order feed simulator from the web; everything else is
 * refused. The page talks to the simulator only through {@code SimulatorControl}; the tests read the real simulator to see
 * that the click really reached it.
 */
class SimulatorWebTest extends WebTestBase {

    private HttpTestClient admin;
    private HttpTestClient staff;

    @BeforeEach
    void sessions() throws Exception {
        if (admin == null) {
            admin = app.adminBrowser();
            staff = app.staffBrowser();
        }
        execute("UPDATE inventory SET stock_qty = 1000, reorder_level = 10");
    }

    @AfterEach
    void stopTheSimulator() {
        app.appContext().simulator().stop();
        app.appContext().simulator().setRate(60);
    }

    private Reply post(HttpTestClient browser, Map<String, String> fields) {
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("_csrf", TestApp.tokenOf(browser));
        return browser.postForm("/admin/simulator", form);
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private String page() {
        return plain(admin.get("/admin/simulator").body());
    }

    @Test
    void theAdministratorSeesTheStateTheLimitAndAFormWithTheThreeActions() {
        Reply reply = admin.get("/admin/simulator");
        assertEquals(200, reply.status());
        String text = plain(reply.body());
        assertTrue(text.contains("State stopped"), text);
        assertTrue(text.contains("Highest allowed rate 6000"), text);
        assertTrue(reply.body().contains("name=\"action\" value=\"start\"") && reply.body().contains("name=\"action\" value=\"rate\"")
                && reply.body().contains("name=\"action\" value=\"stop\""));
        assertTrue(reply.body().contains("name=\"_csrf\""));
        assertFalse(reply.body().contains("http-equiv=\"refresh\""));
    }

    @Test
    void staffAnonymousAndOtherRolesAreRefused() {
        assertEquals(403, staff.get("/admin/simulator").status());
        assertEquals(403, post(staff, Map.of("action", "start", "ratePerMinute", "60")).status());
        assertFalse(app.appContext().simulator().isRunning(), "a refused request did not start the simulator");
        HttpTestClient.assertRedirect(app.browser().get("/admin/simulator"), "/login");
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/admin/simulator", Map.of("action", "start", "ratePerMinute", "60", "_csrf", token)), "/login");
        assertFalse(app.appContext().simulator().isRunning());
    }

    @Test
    void withoutAValidTokenNothingHappens() {
        assertEquals(403, admin.postForm("/admin/simulator", Map.of("action", "start", "ratePerMinute", "60")).status());
        assertEquals(403, admin.postForm("/admin/simulator", Map.of("action", "start", "ratePerMinute", "60", "_csrf", "forged")).status());
        assertFalse(app.appContext().simulator().isRunning());
    }

    @Test
    void startRunsTheRealSimulatorAndItsOrdersAppearInTheOrderList() throws Exception {
        Reply start = post(admin, Map.of("action", "start", "ratePerMinute", "6000"));
        HttpTestClient.assertRedirect(start, "/admin/simulator");
        assertEquals("", start.body());
        String text = page();
        assertTrue(text.contains("The feed simulator has been started at 6000 orders per minute."), text);
        assertTrue(text.contains("State running") && text.contains("Rate (orders per minute) 6000"), text);
        assertTrue(app.appContext().simulator().isRunning());
        assertEquals(6000, app.appContext().simulator().getRate());
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> app.appContext().simulator().produced() > 0), "the simulator produced orders");
        assertTrue(count("SELECT COUNT(*) FROM orders WHERE external_ref LIKE 'SIM-%'") > 0, "and they are in the database");
        assertTrue(plain(staff.get("/dashboard").body()).contains("Simulator running"), "the dashboard shows it too");
        assertTrue(admin.get("/orders").body().contains("SIM-"), "and in the order list");
    }

    @Test
    void changingTheRateWhileRunningKeepsItRunningAndStopStopsIt() {
        post(admin, Map.of("action", "start", "ratePerMinute", "600"));
        HttpTestClient.assertRedirect(post(admin, Map.of("action", "rate", "ratePerMinute", "1200")), "/admin/simulator");
        assertTrue(page().contains("The rate is now 1200 orders per minute."));
        assertTrue(app.appContext().simulator().isRunning());
        assertEquals(1200, app.appContext().simulator().getRate());

        HttpTestClient.assertRedirect(post(admin, Map.of("action", "stop")), "/admin/simulator");
        String text = page();
        assertTrue(text.contains("The feed simulator has been stopped.") && text.contains("State stopped"), text);
        assertFalse(app.appContext().simulator().isRunning());
        assertEquals(1200, app.appContext().simulator().getRate(), "the chosen rate is kept");
    }

    @Test
    void changingTheRateWhileStoppedOnlyRemembersItAndSaysSo() {
        HttpTestClient.assertRedirect(post(admin, Map.of("action", "rate", "ratePerMinute", "90")), "/admin/simulator");
        String text = page();
        assertTrue(text.contains("The rate is now 90 orders per minute; it applies when the simulator is started."), text);
        assertFalse(app.appContext().simulator().isRunning());
        assertEquals(90, app.appContext().simulator().getRate());
    }

    @Test
    void startingTwiceAndStoppingWhileStoppedAreHarmlessAndExplained() {
        assertEquals(302, post(admin, Map.of("action", "stop")).status());
        assertTrue(page().contains("The feed simulator was not running."));
        post(admin, Map.of("action", "start", "ratePerMinute", "60"));
        page();
        post(admin, Map.of("action", "start", "ratePerMinute", "120"));
        assertTrue(page().contains("The feed simulator is already running"));
        assertEquals(60, app.appContext().simulator().getRate(), "the second start did not change the rate");
    }

    @Test
    void ratesThatAreNotWholeNumbersInRangeAreRefusedWithAMessageAndChangeNothing() {
        for (String bad : List.of("0", "-5", "6001", "abc", "", "1.5", " ", "99999999999", "1e3", "٣")) {
            for (String action : List.of("start", "rate")) {
                Reply reply = post(admin, Map.of("action", action, "ratePerMinute", bad));
                HttpTestClient.assertRedirect(reply, "/admin/simulator");
                assertTrue(page().contains("The rate must be a whole number of orders per minute between 1 and 6000."), action + " " + bad);
            }
        }
        Reply missing = post(admin, Map.of("action", "start"));
        HttpTestClient.assertRedirect(missing, "/admin/simulator");
        assertTrue(page().contains("The rate must be"));
        assertFalse(app.appContext().simulator().isRunning(), "no invalid request started it");
        assertEquals(60, app.appContext().simulator().getRate());
    }

    @Test
    void anUnknownOrMissingActionIsABadRequest() {
        for (Map<String, String> form : List.of(Map.of("ratePerMinute", "60"), Map.of("action", "restart"), Map.of("action", "START"), Map.of("action", ""))) {
            Reply reply = post(admin, form);
            assertEquals(400, reply.status(), form.toString());
            assertFalse(reply.body().contains("Exception"));
        }
        assertFalse(app.appContext().simulator().isRunning());
    }

    @Test
    void theSimulatorIsReachedOnlyThroughTheServiceInterface() throws Exception {
        assertTrue(app.services().simulator() == app.appContext().simulator(), "AppServices holds the real simulator as a SimulatorControl");
        assertTrue(app.services().simulator() instanceof com.cloudkitchen.service.SimulatorControl);
    }
}
