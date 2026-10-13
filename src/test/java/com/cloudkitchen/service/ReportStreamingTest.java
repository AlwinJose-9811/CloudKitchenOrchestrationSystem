package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.ReportRow;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.CsvReportExporter;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.ServiceHarness;
import com.cloudkitchen.testsupport.SyntheticReportDao;
import com.cloudkitchen.util.TimeUtil;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * D-078: the download streams the report page by page (500 rows per query, each with its own pooled connection), has no row
 * cap, produces exactly the bytes of {@code CsvReportExporter.write}, and never ends silently after a failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportStreamingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private ServiceHarness h;
    private CsvReportExporter exporter;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
        exporter = new CsvReportExporter(AppFixture.scratchDirectory());
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private OrderDao fakeDao(int total, AtomicInteger queries, List<Integer> pageSizes, int failOnQuery) {
        return SyntheticReportDao.of(total, queries, pageSizes, failOnQuery);
    }

    private String expected(int total) throws IOException {
        StringWriter out = new StringWriter();
        exporter.write(out, SyntheticReportDao.rows(total));
        return out.toString();
    }

    @Test
    void theStreamIsByteIdenticalToTheFileFormatAtEveryPageBoundaryAndHasNoRowCap() throws Exception {
        for (int total : new int[] {0, 1, 499, 500, 501, 1000, 1234, 2501}) {
            AtomicInteger queries = new AtomicInteger();
            List<Integer> pages = new ArrayList<>();
            ReportService service = new ReportService(h.db.database(), fakeDao(total, queries, pages, -1), exporter);
            StringWriter out = new StringWriter();
            service.streamDaily(DAY, out);
            assertEquals(expected(total), out.toString(), total + " rows");
            assertEquals(total / ReportService.STREAM_PAGE_SIZE + 1, queries.get(), total + " rows need one query per page and one that finds the end");
            assertTrue(pages.stream().allMatch(size -> size <= ReportService.STREAM_PAGE_SIZE), "no query reads more than a page: " + pages);
        }
    }

    @Test
    void noConnectionIsHeldWhileTheClientReceivesData() throws Exception {
        ReportService service = new ReportService(h.db.database(), fakeDao(1200, new AtomicInteger(), new ArrayList<>(), -1), exporter);
        List<Integer> activeWhileWriting = new ArrayList<>();
        Writer observer = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                activeWhileWriting.add(h.db.pool().getHikariPoolMXBean().getActiveConnections());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        service.streamDaily(DAY, observer);
        assertTrue(activeWhileWriting.size() > 1000, "rows were written one by one: " + activeWhileWriting.size());
        assertTrue(activeWhileWriting.stream().allMatch(active -> active == 0), "a slow client never holds a pooled connection");
    }

    @Test
    void aFailureAfterTheFirstPageIsAnExceptionNeverASilentlyShortFile() {
        ReportService service = new ReportService(h.db.database(), fakeDao(1200, new AtomicInteger(), new ArrayList<>(), 2), exporter);
        StringWriter out = new StringWriter();
        assertThrows(CloudKitchenException.class, () -> service.streamDaily(DAY, out));
        assertTrue(out.toString().length() > 0 && out.toString().split("\r\n").length == 501, "the first page had already been written; the caller must abort the download");
    }

    @Test
    void aFailureOfTheFirstQueryLeavesOnlyTheHeaderInTheBufferForTheCallerToDiscard() {
        ReportService service = new ReportService(h.db.database(), fakeDao(10, new AtomicInteger(), new ArrayList<>(), 1), exporter);
        StringWriter out = new StringWriter();
        assertThrows(CloudKitchenException.class, () -> service.streamDaily(DAY, out));
        assertEquals(1, out.toString().split("\r\n").length, "only the header was written");
    }

    @Test
    void theDatabasePagingReturnsTheSameRowsAsTheFullQueryInChunksOfAnySize() throws Exception {
        h.reset();
        h.clock.set(Instant.parse("2026-10-10T09:00:00Z"));
        for (int i = 1; i <= 7; i++) {
            h.clock.set(Instant.parse("2026-10-10T09:00:00Z").plusSeconds(i));
            h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "ST-" + i, "BIRYANI", "Raita", 1));
        }
        h.clock.set(Instant.parse("2026-10-11T00:00:00Z"));
        h.service.submitOrder(h.t.request(OrderSource.SWIGGY, "ST-NEXT-DAY", "BIRYANI", "Raita", 1));

        Instant from = TimeUtil.startOfUtcDay(DAY);
        Instant to = TimeUtil.endOfUtcDayExclusive(DAY);
        List<ReportRow> whole = h.db.database().withConnection(c -> h.t.orders.findForReport(c, from, to));
        assertEquals(7, whole.size());
        for (int limit : new int[] {1, 2, 3, 7, 8, 500}) {
            List<ReportRow> paged = new ArrayList<>();
            long after = 0;
            while (true) {
                long cursor = after;
                List<ReportRow> page = h.db.database().withConnection(c -> h.t.orders.findForReportAfter(c, from, to, cursor, limit));
                assertTrue(page.size() <= limit);
                paged.addAll(page);
                if (page.size() < limit) {
                    break;
                }
                after = page.get(page.size() - 1).orderId();
            }
            assertEquals(whole, paged, "limit " + limit);
        }
        assertThrows(IllegalArgumentException.class, () -> h.db.database().withConnection(c -> h.t.orders.findForReportAfter(c, from, to, 0, 0)));

        ReportService service = new ReportService(h.db.database(), h.t.orders, exporter);
        StringWriter streamed = new StringWriter();
        service.streamDaily(DAY, streamed);
        StringWriter written = new StringWriter();
        service.writeDaily(DAY, written);
        assertEquals(written.toString(), streamed.toString(), "the stream is the same text as the buffered download");
    }
}
