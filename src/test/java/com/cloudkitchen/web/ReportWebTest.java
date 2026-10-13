package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.io.CsvReportExporter;
import com.cloudkitchen.service.ReportService;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.SyntheticReportDao;
import com.cloudkitchen.web.support.AppServices;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FR-FIL-03, D-078, SDD 10.2 routes 21 and 22: the administrator downloads the daily CSV. The date is parsed strictly, the
 * file name is built from the parsed date, the body is the report text, large reports are streamed in chunks without a row
 * cap, and a failure never produces a file that looks complete. ADMIN only.
 */
class ReportWebTest extends WebTestBase {

    private HttpTestClient admin;
    private HttpTestClient staff;
    private CsvReportExporter exporter;

    @BeforeEach
    void freshState() throws Exception {
        if (admin == null) {
            admin = app.adminBrowser();
            staff = app.staffBrowser();
            exporter = new CsvReportExporter(AppFixture.scratchDirectory());
        }
        execute("UPDATE inventory SET stock_qty = 100, reorder_level = 10");
        pauseAll();
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private AppServices withReports(ReportService reports) {
        return app.replaceServices(s -> new AppServices(s.config(), s.clock(), s.auth(), s.orders(), s.orderQueries(), s.dashboard(), s.menu(),
                s.stock(), reports, s.simulator(), s.compensation(), s.workers(), s.snapshots()));
    }

    @Test
    void thePickerPageHasADateFormThatReachesTheDownloadRoute() {
        Reply page = admin.get("/admin/reports");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("action=\"/admin/reports/daily.csv\"") && page.body().contains("name=\"date\"") && page.body().contains("type=\"date\""));
        assertTrue(page.body().contains("value=\"" + today() + "\""), "today (UTC) is preselected");
        String reportForm = page.body().substring(page.body().indexOf("id=\"report-form\""));
        reportForm = reportForm.substring(0, reportForm.indexOf("</form>"));
        assertFalse(reportForm.contains("_csrf"), "a download changes nothing, so it is a GET without a token");
    }

    @Test
    void theDownloadHasTheCsvHeadersAndTheBodyOfTheReport() throws Exception {
        submit(OrderSource.SWIGGY, "DL-1", "BIRYANI", "Asha Rao", "Chicken Biryani", 2);
        submit(OrderSource.ZOMATO, "DL-2", "BURGER", "Ravi Kumar", "Classic Burger", 1);
        Reply reply = admin.get("/admin/reports/daily.csv?date=" + today());
        assertEquals(200, reply.status());
        assertEquals("text/csv;charset=UTF-8", reply.headers().firstValue("Content-Type").orElse("").replace("; ", ";"));
        assertEquals("attachment; filename=\"orders-" + today() + ".csv\"", reply.headers().firstValue("Content-Disposition").orElse(""));
        assertEquals("no-store", reply.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(reply.body().startsWith("order_id,created_at_utc,source,brand,external_ref,status,"), reply.body());
        assertTrue(reply.body().contains(",DL-1,") && reply.body().contains(",DL-2,"));
        assertFalse(reply.body().contains("Asha") || reply.body().contains("Ravi"), "no customer data");
        StringWriter expected = new StringWriter();
        app.services().reports().writeDaily(LocalDate.now(ZoneOffset.UTC), expected);
        assertEquals(expected.toString(), reply.body());
    }

    @Test
    void aDayWithoutOrdersIsTheHeaderOnly() {
        Reply reply = admin.get("/admin/reports/daily.csv?date=2001-01-01");
        assertEquals(200, reply.status());
        assertEquals(1, reply.body().split("\r\n").length);
        assertEquals("attachment; filename=\"orders-2001-01-01.csv\"", reply.headers().firstValue("Content-Disposition").orElse(""));
    }

    @Test
    void theDateMustBeARealCalendarDayInExactlyThatFormOtherwise400() {
        List<String> bad = new ArrayList<>(List.of("", " ", "2026-13-01", "2026-02-30", "2026-2-3", "20261011", "2026/10/11", "11-10-2026",
                "2026-10-11'; DROP TABLE orders;--", "../../etc/passwd", "2026-10-11\r\nSet-Cookie: x=1", "２０２６-１０-１１", "today", "2026-10-11 ", "+2026-10-11"));
        for (String date : bad) {
            Reply reply = admin.get("/admin/reports/daily.csv?date=" + URLEncoder.encode(date, StandardCharsets.UTF_8));
            assertEquals(400, reply.status(), "[" + date + "]");
            assertFalse(reply.body().contains("order_id,"), "no CSV for a bad date");
            assertTrue(reply.headers().firstValue("Content-Disposition").isEmpty(), "no download header for a bad date");
            assertTrue(reply.headers().firstValue("Set-Cookie").map(c -> !c.contains("x=1")).orElse(true));
        }
        assertEquals(400, admin.get("/admin/reports/daily.csv").status(), "missing date");
        assertEquals(200, admin.get("/admin/reports/daily.csv?date=2026-10-11&date=garbage").status(), "only the first value counts");
    }

    @Test
    void aLeapDayIsAcceptedOnlyInALeapYear() {
        assertEquals(200, admin.get("/admin/reports/daily.csv?date=2028-02-29").status());
        assertEquals(400, admin.get("/admin/reports/daily.csv?date=2026-02-29").status());
    }

    @Test
    void aLargeReportIsStreamedInChunksWithoutARowCap() {
        AppServices original = withReports(new ReportService(app.appContext().database(),
                SyntheticReportDao.of(2501, new AtomicInteger(), new ArrayList<>(), -1), exporter));
        try {
            Reply reply = admin.get("/admin/reports/daily.csv?date=2026-10-10");
            assertEquals(200, reply.status());
            assertTrue(reply.headers().firstValue("Transfer-Encoding").orElse("").contains("chunked"), "no Content-Length: the body was not collected first");
            assertTrue(reply.headers().firstValue("Content-Length").isEmpty());
            assertEquals(2502, reply.body().split("\r\n").length, "header + 2501 rows: no cap");
            StringWriter expected = new StringWriter();
            try {
                exporter.write(expected, SyntheticReportDao.rows(2501));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            assertEquals(expected.toString(), reply.body());
        } finally {
            app.restoreServices(original);
        }
    }

    @Test
    void aFailureBeforeAnythingWasSentIsTheGenericErrorPageAndNotACsv() {
        AppServices original = withReports(new ReportService(app.appContext().database(),
                SyntheticReportDao.of(10, new AtomicInteger(), new ArrayList<>(), 1), exporter));
        try {
            Reply reply = admin.get("/admin/reports/daily.csv?date=2026-10-10");
            assertEquals(500, reply.status());
            assertFalse(reply.body().contains("order_id,"), "the header line that was buffered is discarded");
            assertFalse(reply.body().contains("SQLException") || reply.body().contains("simulated failure"), "no internal detail");
            assertTrue(reply.headers().firstValue("Content-Disposition").isEmpty());
        } finally {
            app.restoreServices(original);
        }
    }

    @Test
    void aFailureAfterTheDownloadStartedAbortsTheConnectionSoNoTruncatedFileLooksComplete() {
        AppServices original = withReports(new ReportService(app.appContext().database(),
                SyntheticReportDao.of(5000, new AtomicInteger(), new ArrayList<>(), 3), exporter));
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> admin.get("/admin/reports/daily.csv?date=2026-10-10"));
            assertNotNull(failure.getMessage());
            assertTrue(failure.getMessage().contains("/admin/reports/daily.csv"), failure.getMessage());
        } finally {
            app.restoreServices(original);
        }
        assertEquals(200, admin.get("/admin/reports/daily.csv?date=2001-01-01").status(), "the server carries on");
    }

    @Test
    void staffVisitorsAndOtherMethodsAreRefused() {
        assertEquals(403, staff.get("/admin/reports").status());
        assertEquals(403, staff.get("/admin/reports/daily.csv?date=" + today()).status());
        HttpTestClient.assertRedirect(app.browser().get("/admin/reports"), "/login");
        HttpTestClient.assertRedirect(app.browser().get("/admin/reports/daily.csv?date=" + today()), "/login");
        Reply post = admin.post("/admin/reports/daily.csv", "date=" + today());
        assertEquals(403, post.status(), "a POST without a token never reaches the servlet");
    }
}
