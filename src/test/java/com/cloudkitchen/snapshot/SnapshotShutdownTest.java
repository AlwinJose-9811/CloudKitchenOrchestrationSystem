package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.pipeline.ShutdownCoordinator;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-SER-02, FR-SER-05, FR-RES-05 with the whole application: the scheduled and the final snapshot, a final snapshot that
 * cannot be written, a tampered snapshot at start-up, and the restoration of a lost database from the final snapshot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotShutdownTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private AppContext startApp(Properties properties) throws CloudKitchenException {
        return AppContext.create(AppConfig.fromProperties(properties), h.db.jdbcUrl(), Clock.systemUTC(), null, false);
    }

    private Properties slowDelivery(Path snapshotDir) {
        Properties p = AppFixture.properties(h.db, "worker.delivery.ms", "60000", "shutdown.drain.seconds", "1", "shutdown.await.seconds", "1",
                "snapshot.interval.seconds", "1");
        p.setProperty("snapshot.dir", snapshotDir.toString());
        return p;
    }

    private long status(String sql, Object... params) throws Exception {
        return h.t.number(sql, params);
    }

    private boolean statusIs(long id, String expected) {
        try {
            return expected.equals(h.t.text("SELECT status FROM orders WHERE id = ?", id));
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<Long> ids(List<OrderSnapshotDTO> dtos) {
        Set<Long> ids = new HashSet<>();
        dtos.forEach(d -> ids.add(d.orderId()));
        return ids;
    }

    private static ShutdownCoordinator.StepResult step(AppContext app, String name) {
        return app.shutdownCoordinator().outcome().steps().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theScheduledAndTheFinalSnapshotHoldExactlyTheInFlightOrdersAndRestoreALostDatabase() throws Exception {
        h.reset();
        Path snapshotDir = AppFixture.scratchDirectory().resolve("snapshots");
        AppContext app = startApp(slowDelivery(snapshotDir));
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        SubmissionResult stuck = app.orders().submitOrder(h.t.request(OrderSource.ZOMATO, "SS-STUCK", "BIRYANI", "Chicken Biryani", 1));
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(30), () -> statusIs(stuck.orderId(), "OUT_FOR_DELIVERY")), "the worker is inside its 60 s delivery step");
        long queued = app.orders().submitOrder(h.t.request(OrderSource.SWIGGY, "SS-QUEUED", "BIRYANI", "Raita", 2)).orderId();
        long cancelled = app.orders().submitOrder(h.t.request(OrderSource.SWIGGY, "SS-CANCELLED", "BIRYANI", "Raita", 1)).orderId();
        assertTrue(app.orders().cancelOrder(cancelled, "staff"));
        // FR-RES-05: the periodic task writes on its own
        assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
            try (var files = Files.list(snapshotDir)) {
                return files.anyMatch(p -> p.getFileName().toString().endsWith(".ser.hmac"));
            } catch (java.io.IOException e) {
                return false;
            }
        }), "a periodic snapshot appeared");
        assertTrue(app.snapshotTask().stats().runs() >= 1);

        app.close();
        ShutdownCoordinator.StepResult finalStep = step(app, "write the final snapshot");
        assertFalse(finalStep.failed());
        assertTrue(finalStep.note().startsWith("written snapshot-"), finalStep.note());
        assertFalse(app.shutdownCoordinator().outcome().anyFailed());
        assertEquals(Set.of(stuck.orderId(), queued), ids(app.snapshots().readLatestValid().orElseThrow()),
                "the final snapshot holds exactly the non-terminal orders (the cancelled one is not in it)");

        // the database is lost: only the snapshot remains
        for (long id : new long[] {stuck.orderId(), queued, cancelled}) {
            h.t.execute("DELETE FROM order_status_history WHERE order_id = ?", id);
            h.t.execute("DELETE FROM order_items WHERE order_id = ?", id);
            h.t.execute("DELETE FROM orders WHERE id = ?", id);
        }
        Properties fast = AppFixture.properties(h.db, "snapshot.interval.seconds", "3600");
        fast.setProperty("snapshot.dir", snapshotDir.toString());
        AppContext next = startApp(fast);
        try {
            assertTrue(next.awaitReady(60, TimeUnit.SECONDS));
            assertEquals(2, next.recovery().stats().snapshotInserted(), "both in-flight orders came back from the signed snapshot");
            assertTrue(PipelineHarness.awaitCondition(Duration.ofSeconds(60), () -> {
                try {
                    return status("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'") == 2;
                } catch (Exception e) {
                    return false;
                }
            }), "the restored orders were queued and delivered like any recovered order");
            assertEquals(0, status("SELECT COUNT(*) FROM orders WHERE external_ref = 'SS-CANCELLED'"), "a terminal order is not restored");
            assertEquals(2, status("SELECT COUNT(DISTINCT order_id) FROM order_status_history WHERE note = 'RECOVERED_FROM_SNAPSHOT'"));
            assertEquals(0, status("SELECT COUNT(*) FROM orders WHERE stock_deducted = 1"));
        } finally {
            next.close();
        }
    }

    @Test
    void aFinalSnapshotThatCannotBeWrittenIsReportedAndTheShutdownStillCompletes() throws Exception {
        h.reset();
        Path snapshotDir = AppFixture.scratchDirectory().resolve("snapshots");
        AppContext app = startApp(slowDelivery(snapshotDir));
        assertTrue(app.awaitReady(60, TimeUnit.SECONDS));
        app.orders().submitOrder(h.t.request(OrderSource.ZOMATO, "SF-1", "BIRYANI", "Raita", 1));
        // the directory disappears and a file takes its place (permissions or a full disk give the same kind of failure)
        Path moved = snapshotDir.resolveSibling("snapshots-moved");
        app.snapshotTask().stop();
        Files.move(snapshotDir, moved);
        Files.writeString(snapshotDir, "a file where the directory was", StandardCharsets.UTF_8);
        app.close();
        ShutdownCoordinator.StepResult finalStep = step(app, "write the final snapshot");
        assertFalse(finalStep.failed(), "reported in the note, not a failed step");
        assertTrue(finalStep.note().startsWith("NOT WRITTEN"), finalStep.note());
        assertTrue(app.shutdownCoordinator().outcome().complete());
        assertEquals("close the event log", app.shutdownCoordinator().outcome().steps().get(app.shutdownCoordinator().outcome().steps().size() - 1).name(),
                "every later step still ran");
        assertTrue(Files.readString(app.eventLog().path(), StandardCharsets.UTF_8).contains("event=SHUTDOWN_COMPLETED"));
    }

    @Test
    void aTamperedSnapshotAtStartupIsRejectedLoggedAndDoesNotPreventTheStart() throws Exception {
        h.reset();
        Path snapshotDir = AppFixture.scratchDirectory().resolve("snapshots");
        AppContext first = startApp(slowDelivery(snapshotDir));
        assertTrue(first.awaitReady(60, TimeUnit.SECONDS));
        first.orders().submitOrder(h.t.request(OrderSource.ZOMATO, "TS-1", "BIRYANI", "Raita", 1));
        first.close();
        Path ser;
        try (var files = Files.list(snapshotDir)) {
            ser = files.filter(p -> p.getFileName().toString().endsWith(".ser")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(ser);
        bytes[bytes.length / 2] ^= 0x20;
        Files.write(ser, bytes);
        h.t.execute("DELETE FROM order_status_history");
        h.t.execute("DELETE FROM order_items");
        h.t.execute("DELETE FROM orders");

        Properties again = AppFixture.properties(h.db, "snapshot.interval.seconds", "3600");
        again.setProperty("snapshot.dir", snapshotDir.toString());
        AppContext next = startApp(again);
        try {
            assertTrue(next.awaitReady(60, TimeUnit.SECONDS), "the application started although its snapshot was rejected");
            assertEquals(0, next.recovery().stats().snapshotInserted());
            assertEquals(0, status("SELECT COUNT(*) FROM orders"), "nothing was restored from the tampered file");
            next.close();
            assertTrue(Files.readString(next.eventLog().path(), StandardCharsets.UTF_8).contains("event=SNAPSHOT_REJECTED"), "the rejection is in orders.log");
        } finally {
            next.close();
        }
    }
}
