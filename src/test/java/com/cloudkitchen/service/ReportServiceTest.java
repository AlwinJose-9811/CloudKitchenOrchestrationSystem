package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.ReportRow;
import com.cloudkitchen.io.CsvReportExporter;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-FIL-03: the report holds real orders of exactly one UTC day, no customer data, and neutralises a formula in a reference. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private long submitAt(String instant, String ref, String brand, Object... items) throws Exception {
        h.clock.set(Instant.parse(instant));
        return h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, brand, items)).orderId();
    }

    @Test
    void theReportContainsExactlyTheOrdersCreatedOnTheUtcDay() throws Exception {
        h.reset();
        submitAt("2026-10-09T23:59:59.999Z", "RP-BEFORE", "BIRYANI", "Raita", 1);
        long first = submitAt("2026-10-10T00:00:00.000Z", "RP-FIRST", "BIRYANI", "Chicken Biryani", 2, "Raita", 1);
        long middle = submitAt("2026-10-10T12:30:00.500Z", "-cmd", "BURGER", "Classic Burger", 1);
        submitAt("2026-10-11T00:00:00.000Z", "RP-AFTER", "BIRYANI", "Raita", 1);
        h.service.cancelOrder(middle, "staff");

        ReportService service = new ReportService(h.db.database(), h.t.orders, new CsvReportExporter(AppFixture.scratchDirectory()));
        List<ReportRow> rows = service.rowsOf(DAY);
        assertEquals(List.of(first, middle), rows.stream().map(ReportRow::orderId).toList(), "[00:00:00Z, next day 00:00:00Z) on created_at");
        assertEquals(3, rows.get(0).itemCount(), "units ordered");
        assertEquals("547.00", rows.get(0).subtotal().toPlainString());
        assertEquals("BIRYANI", rows.get(0).brand());
        assertEquals("CANCELLED", rows.get(1).status().name());

        Path file = service.exportDaily(DAY);
        assertEquals("orders-2026-10-10.csv", file.getFileName().toString());
        String csv = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length, "the header and one row per order created that day");
        assertTrue(lines[1].startsWith(first + ",2026-10-10T00:00:00.000Z,SWIGGY,BIRYANI,RP-FIRST,ROUTED,3,547.00,"), lines[1]);
        assertTrue(lines[2].contains(",'-cmd,"), "a reference that starts like a formula (the validator allows '-') is neutralised: " + lines[2]);
        assertFalse(csv.contains("Asha"), "there is no customer column");

        StringWriter download = new StringWriter();
        service.writeDaily(DAY, download);
        assertEquals(csv, download.toString(), "the download is the same text as the file");
    }

    @Test
    void aDayWithoutOrdersGivesAFileWithTheHeaderOnly() throws Exception {
        h.reset();
        ReportService service = new ReportService(h.db.database(), h.t.orders, new CsvReportExporter(AppFixture.scratchDirectory()));
        Path file = service.exportDaily(LocalDate.of(2001, 1, 1));
        assertEquals(1, Files.readAllLines(file, StandardCharsets.UTF_8).size());
    }
}
