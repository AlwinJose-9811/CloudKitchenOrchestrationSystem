package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.exception.SnapshotWriteException;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.TestApp;
import com.cloudkitchen.web.support.AppServices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-WEB-10, FR-CON-11, FR-WEB-13, SDD 10.2 routes 18 to 20: the worker panel pauses, resumes and restarts a station worker
 * through {@code WorkerControl}, a restart carries the generation the page showed (D-079), and the administrator can write a
 * snapshot on demand. Everything is ADMIN only and POST + CSRF.
 */
class WorkerPanelWebTest extends WebTestBase {

    private HttpTestClient admin;
    private HttpTestClient staff;

    @BeforeEach
    void freshState() {
        if (admin == null) {
            admin = app.adminBrowser();
            staff = app.staffBrowser();
        }
        admin.get("/orders");
        resumeAll();
    }

    @AfterEach
    void running() {
        resumeAll();
    }

    private static String plain(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private Reply post(HttpTestClient browser, String path, Map<String, String> fields) {
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("_csrf", TestApp.tokenOf(browser));
        return browser.postForm(path, form);
    }

    private String after(Reply reply) {
        HttpTestClient.assertRedirect(reply, "/admin/workers");
        assertEquals("", reply.body());
        return plain(admin.get("/admin/workers").body());
    }

    private StationView view(String code) {
        return app.appContext().orchestrator().stationViews().stream().filter(s -> s.stationCode().equals(code)).findFirst().orElseThrow();
    }

    private void awaitState(String code, WorkerState state) {
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(15), () -> view(code).worker().state() == state), code + " -> " + state + ": " + view(code));
    }

    @Test
    void thePanelShowsEveryStationWithStateGenerationQueueAndAButtonPerAction() {
        Reply page = admin.get("/admin/workers");
        assertEquals(200, page.status());
        String text = plain(page.body());
        for (String code : new String[] {"BIRYANI", "BURGER", "PIZZA"}) {
            assertTrue(text.contains(code), code);
            assertTrue(page.body().contains("name=\"stationCode\" value=\"" + code + "\""));
        }
        assertTrue(text.contains("Generation") && text.contains("Heartbeat age") && text.contains("Queue") && text.contains("Processed"));
        assertTrue(page.body().contains("name=\"generation\" value=\"" + view("BIRYANI").worker().generation() + "\""));
        assertTrue(page.body().contains("name=\"action\" value=\"restart\"") && page.body().contains("name=\"action\" value=\"pause\""));
        assertTrue(page.body().contains("id=\"snapshot-form\"") && page.body().contains("name=\"_csrf\""));
        assertFalse(page.body().contains("http-equiv=\"refresh\""));
    }

    @Test
    void pauseAndResumeReachTheRealWorker() {
        String text = after(post(admin, "/admin/workers", Map.of("action", "pause", "stationCode", "BURGER")));
        assertTrue(text.contains("takes no new order after the one it is working on"), text);
        awaitState("BURGER", WorkerState.PAUSED);
        assertTrue(view("BURGER").paused());
        assertTrue(plain(admin.get("/admin/workers").body()).contains("(paused)"));
        assertTrue(admin.get("/admin/workers").body().contains("name=\"action\" value=\"resume\""));

        text = after(post(admin, "/admin/workers", Map.of("action", "resume", "stationCode", "BURGER")));
        assertTrue(text.contains("running again"), text);
        awaitState("BURGER", WorkerState.IDLE);
        assertFalse(view("BURGER").paused());
    }

    @Test
    void restartWithTheCurrentGenerationStartsTheNextOne() {
        int shown = view("PIZZA").worker().generation();
        String text = after(post(admin, "/admin/workers", Map.of("action", "restart", "stationCode", "PIZZA", "generation", Integer.toString(shown))));
        assertTrue(text.contains("was restarted (generation " + (shown + 1) + ")"), text);
        assertEquals(shown + 1, view("PIZZA").worker().generation());
        assertEquals(1, view("PIZZA").worker().restarts(), "one restart is counted");
        awaitState("PIZZA", WorkerState.IDLE);
    }

    @Test
    void aStaleGenerationRestartsNothingAndSaysSo() {
        int shown = view("BIRYANI").worker().generation();
        after(post(admin, "/admin/workers", Map.of("action", "restart", "stationCode", "BIRYANI", "generation", Integer.toString(shown))));
        int now = view("BIRYANI").worker().generation();
        assertNotEquals(shown, now);
        int restarts = view("BIRYANI").worker().restarts();

        String text = after(post(admin, "/admin/workers", Map.of("action", "restart", "stationCode", "BIRYANI", "generation", Integer.toString(shown))));
        assertTrue(text.contains("out of date") && text.contains("Nothing was restarted"), text);
        assertEquals(now, view("BIRYANI").worker().generation(), "the healthy replacement was left alone");
        assertEquals(restarts, view("BIRYANI").worker().restarts());
    }

    @Test
    void aMissingOrMalformedGenerationAnUnknownStationAndANegativeGenerationChangeNothing() {
        int generation = view("BURGER").worker().generation();
        for (Map<String, String> bad : new Map[] {Map.of("action", "restart", "stationCode", "BURGER"),
                Map.of("action", "restart", "stationCode", "BURGER", "generation", "abc"),
                Map.of("action", "restart", "stationCode", "BURGER", "generation", "-1"),
                Map.of("action", "restart", "stationCode", "BURGER", "generation", "")}) {
            String text = after(post(admin, "/admin/workers", bad));
            assertTrue(text.contains("nothing was restarted"), bad + ": " + text);
        }
        assertEquals(generation, view("BURGER").worker().generation());

        for (String action : new String[] {"pause", "resume", "restart"}) {
            String text = after(post(admin, "/admin/workers", Map.of("action", action, "stationCode", "NOPE", "generation", "0")));
            assertTrue(text.contains("does not exist"), action + ": " + text);
        }
        assertTrue(after(post(admin, "/admin/workers", Map.of("action", "pause"))).contains("does not exist"));
        assertFalse(view("BURGER").paused());
    }

    @Test
    void anUnknownActionIsA400() {
        assertEquals(400, post(admin, "/admin/workers", Map.of("action", "kill", "stationCode", "BURGER")).status());
        assertEquals(400, post(admin, "/admin/workers", Map.of("stationCode", "BURGER")).status());
    }

    @Test
    void staffVisitorsAndRequestsWithoutATokenAreRefusedAndTheWorkerIsUntouched() {
        int generation = view("BURGER").worker().generation();
        assertEquals(403, staff.get("/admin/workers").status());
        assertEquals(403, post(staff, "/admin/workers", Map.of("action", "pause", "stationCode", "BURGER")).status());
        HttpTestClient.assertRedirect(app.browser().get("/admin/workers"), "/login");
        assertEquals(403, admin.postForm("/admin/workers", Map.of("action", "pause", "stationCode", "BURGER")).status());
        assertEquals(403, admin.postForm("/admin/workers", Map.of("action", "restart", "stationCode", "BURGER", "generation", Integer.toString(generation), "_csrf", "forged")).status());
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/admin/workers", Map.of("action", "pause", "stationCode", "BURGER", "_csrf", token)), "/login");
        assertFalse(view("BURGER").paused());
        assertEquals(generation, view("BURGER").worker().generation());
    }

    // ---- snapshot on demand ----

    private long snapshotFiles() throws IOException {
        Path dir = app.appContext().snapshots().directory();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).count();
        }
    }

    @Test
    void aSnapshotIsWrittenOnDemandAndTheAnswerNamesTheFileButNotTheDirectory() throws Exception {
        Path dir = app.appContext().snapshots().directory();
        Reply reply = post(admin, "/admin/snapshot", Map.of());
        HttpTestClient.assertRedirect(reply, "/admin/workers");
        String page = admin.get("/admin/workers").body();
        String text = plain(page);
        assertTrue(text.contains("A snapshot was written: snapshot-"), text);
        assertFalse(page.contains(dir.toString()) || page.contains(dir.getFileName().toString() + "/"), "no path is shown");
        String name = text.substring(text.indexOf("snapshot-"), text.indexOf(".ser") + 4);
        assertTrue(Files.isRegularFile(dir.resolve(name)), name);
        assertTrue(snapshotFiles() >= 1);
    }

    @Test
    void aSnapshotThatCannotBeWrittenIsReportedWithoutDetail() throws Exception {
        AppServices original = app.replaceServices(s -> new AppServices(s.config(), s.clock(), s.auth(), s.orders(), s.orderQueries(), s.dashboard(),
                s.menu(), s.stock(), s.reports(), s.simulator(), s.compensation(), s.workers(),
                () -> {
                    throw new SnapshotWriteException("disk full at /secret/path/x", new IOException("no space left"));
                }));
        try {
            Reply reply = post(admin, "/admin/snapshot", Map.of());
            HttpTestClient.assertRedirect(reply, "/admin/workers");
            String page = admin.get("/admin/workers").body();
            assertTrue(plain(page).contains("The snapshot could not be written"), plain(page));
            assertTrue(page.contains("flash-error"));
            assertFalse(page.contains("/secret/path") || page.contains("no space left") || page.contains("disk full"));
        } finally {
            app.restoreServices(original);
        }
    }

    @Test
    void severalSnapshotsRequestedAtTheSameMomentAreSerialisedAndEveryFileIsValid() throws Exception {
        String token = TestApp.tokenOf(admin);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<Reply>> replies = new java.util.ArrayList<>();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 4; i++) {
                replies.add(pool.submit(() -> {
                    go.await();
                    return admin.postForm("/admin/snapshot", Map.of("_csrf", token));
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<Reply> reply : replies) {
                HttpTestClient.assertRedirect(reply.get(60, java.util.concurrent.TimeUnit.SECONDS), "/admin/workers");
            }
        } finally {
            pool.shutdownNow();
        }
        Path dir = app.appContext().snapshots().directory();
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count(), "no half-written file is left behind");
        }
        assertTrue(app.appContext().snapshots().readLatestValid().isPresent(), "the newest snapshot verifies and reads back");
    }

    @Test
    void theSnapshotRouteIsAdminOnlyPostOnlyAndChecksTheToken() throws Exception {
        long before = snapshotFiles();
        assertEquals(403, post(staff, "/admin/snapshot", Map.of()).status());
        assertEquals(403, admin.postForm("/admin/snapshot", Map.of()).status());
        assertEquals(403, admin.postForm("/admin/snapshot", Map.of("_csrf", "forged")).status());
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        HttpTestClient.assertRedirect(visitor.postForm("/admin/snapshot", Map.of("_csrf", token)), "/login");
        assertEquals(405, admin.get("/admin/snapshot").status(), "there is no GET page");
        assertEquals(403, staff.get("/admin/snapshot").status());
        assertEquals(before, snapshotFiles(), "no refused request wrote a file");
    }
}
