package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.pipeline.WorkerHook;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.SimulatedWorkerDeath;
import com.cloudkitchen.testsupport.TestApp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

/**
 * D-093, SDD 7.11.7, SRS FR-CON-11 through the real panel: a station whose restart budget is spent shows {@code DEAD}, and the
 * administrator's Restart brings it back with a fresh budget. The restart budget is 1, so two worker deaths exhaust it. The tests
 * run in order because they walk one station through DEAD, a stale click and the revival.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkerPanelDeadWebTest extends WebTestBase {

    private HttpTestClient admin;
    private HttpTestClient staff;

    @Override
    protected String[] overrides() {
        return new String[] {"worker.restart.max", "1"};
    }

    @BeforeAll
    void sessionsAndADeadStation() throws Exception {
        admin = app.adminBrowser();
        staff = app.staffBrowser();
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        var orchestrator = app.appContext().orchestrator();
        orchestrator.setWorkerHook((stage, owner, id, step) -> {
            if (stage == WorkerHook.Stage.AFTER_POLL && owner.startsWith("BIRYANI#")) {
                throw new SimulatedWorkerDeath("every BIRYANI generation dies");
            }
        });
        for (int i = 1; i <= 3; i++) {
            submit(OrderSource.SWIGGY, "DEAD-" + i, "BIRYANI", "Asha Rao", "Chicken Biryani", 1);
        }
        boolean dead = PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
            orchestrator.repairAll(); // the scheduled monitor does the same; calling it keeps the test independent of its interval
            return orchestrator.runtime("BIRYANI").isDead();
        });
        assertTrue(dead, "the station gave up: " + orchestrator.stationViews());
        orchestrator.setWorkerHook(WorkerHook.NO_OP); // the cause is gone; the operator may now restart
    }

    private StationView biryani() {
        return app.appContext().orchestrator().stationViews().stream().filter(s -> s.stationCode().equals("BIRYANI")).findFirst().orElseThrow();
    }

    private Reply restart(HttpTestClient browser, String generation) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "restart");
        form.put("stationCode", "BIRYANI");
        form.put("generation", generation);
        form.put("_csrf", TestApp.tokenOf(browser));
        return browser.postForm("/admin/workers", form);
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    @Test
    @Order(1)
    void thePanelShowsTheGivenUpStationAsDeadWithItsGenerationAndARestartButton() {
        Reply page = admin.get("/admin/workers");
        String row = page.body().substring(page.body().indexOf("name=\"stationCode\" value=\"BIRYANI\""));
        assertEquals(WorkerState.DEAD, biryani().worker().state());
        assertTrue(plain(page.body()).contains("DEAD"), plain(page.body()));
        assertTrue(row.contains("name=\"generation\" value=\"" + biryani().worker().generation() + "\"") && row.contains("value=\"restart\""));
    }

    @Test
    @Order(2)
    void staffAndForgedRequestsCannotRestartIt() {
        String generation = Integer.toString(biryani().worker().generation());
        assertEquals(403, restart(staff, generation).status());
        assertEquals(403, admin.postForm("/admin/workers", Map.of("action", "restart", "stationCode", "BIRYANI", "generation", generation, "_csrf", "forged")).status());
        assertEquals(WorkerState.DEAD, biryani().worker().state());
    }

    @Test
    @Order(3)
    void aStaleGenerationDoesNotReviveItAndSaysSo() {
        int shown = biryani().worker().generation();
        Reply reply = restart(admin, Integer.toString(shown - 1));
        HttpTestClient.assertRedirect(reply, "/admin/workers");
        String text = plain(admin.get("/admin/workers").body());
        assertTrue(text.contains("out of date") && text.contains("Nothing was restarted"), text);
        assertEquals(WorkerState.DEAD, biryani().worker().state());
        assertEquals(shown, biryani().worker().generation());
    }

    @Test
    @Order(4)
    void theAdministratorsRestartBringsItBackAndTheMessageSaysItHadGivenUp() throws Exception {
        int shown = biryani().worker().generation();
        Reply reply = restart(admin, Integer.toString(shown));
        HttpTestClient.assertRedirect(reply, "/admin/workers");
        assertEquals("", reply.body());
        String page = plain(admin.get("/admin/workers").body());
        assertTrue(page.contains("was restarted (generation " + (shown + 1) + ")") && page.contains("had given up and has a fresh restart budget"), page);
        assertFalse(app.appContext().orchestrator().runtime("BIRYANI").isDead());
        assertEquals(shown + 1, biryani().worker().generation());
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(20), () -> biryani().worker().state() == WorkerState.IDLE), "back to IDLE: " + biryani());
        assertFalse(plain(admin.get("/admin/workers").body()).contains("DEAD"));
    }

    @Test
    @Order(5)
    void theRevivedStationProcessesNewOrders() throws Exception {
        long id = submit(OrderSource.SWIGGY, "DEAD-AFTER", "BIRYANI", "Asha Rao", "Chicken Biryani", 1);
        awaitStatus(id, "DELIVERED");
    }

    @Test
    @Order(6)
    void aSecondClickWithThePreviousGenerationIsStaleNow() {
        int current = biryani().worker().generation();
        Reply reply = restart(admin, Integer.toString(current - 1));
        HttpTestClient.assertRedirect(reply, "/admin/workers");
        assertTrue(plain(admin.get("/admin/workers").body()).contains("out of date"));
        assertEquals(current, biryani().worker().generation(), "the healthy replacement was left alone");
    }
}
