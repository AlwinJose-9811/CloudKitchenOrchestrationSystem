package com.cloudkitchen.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.dto.ReportRow;
import com.cloudkitchen.exception.ReportExportException;
import com.cloudkitchen.testsupport.AppFixture;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** SDD 8.2, FR-FIL-03: RFC 4180 quoting, formula neutralising, determinism, empty days, atomic replacement and write failures. */
class CsvReportExporterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    private static final String HEADER = "order_id,created_at_utc,source,brand,external_ref,status,item_count,subtotal,platform_fee,total,payment_mode,payment_status\r\n";

    private static ReportRow row(long id, String brand, String ref) {
        return new ReportRow(id, Instant.parse("2026-10-10T06:41:12.345Z"), OrderSource.SWIGGY, brand, ref, OrderStatus.DELIVERED, 3,
                new BigDecimal("547.00"), new BigDecimal("27.35"), new BigDecimal("547.00"), PaymentMode.PREPAID_AGGREGATOR, PaymentStatus.PAID);
    }

    private static String render(List<ReportRow> rows) throws IOException {
        StringWriter out = new StringWriter();
        new CsvReportExporter(Path.of(".")).write(out, rows);
        return out.toString();
    }

    @Test
    void writesTheHeaderAndOneCrlfTerminatedRowWithFixedFormats() throws Exception {
        assertEquals(HEADER + "5,2026-10-10T06:41:12.345Z,SWIGGY,BIRYANI,SW-1,DELIVERED,3,547.00,27.35,547.00,PREPAID_AGGREGATOR,PAID\r\n",
                render(List.of(row(5, "BIRYANI", "SW-1"))));
    }

    @Test
    void quotesCommasQuotesAndLineBreaksAndDoublesInnerQuotes() throws Exception {
        String csv = render(List.of(row(1, "A,B", "say \"hi\""), row(2, "line\nbreak", "cr\rhere"), row(3, "both\r\n", "")));
        assertTrue(csv.contains("1,2026-10-10T06:41:12.345Z,SWIGGY,\"A,B\",\"say \"\"hi\"\"\",DELIVERED"), csv);
        assertTrue(csv.contains("\"line\nbreak\",\"cr\rhere\""), csv);
        assertTrue(csv.contains(",\"both\r\n\",,DELIVERED"), "an empty external reference is an empty field: " + csv);
    }

    @Test
    void unicodeIsKeptAsUtf8WithoutAByteOrderMark() throws Exception {
        Path dir = AppFixture.scratchDirectory();
        Path file = new CsvReportExporter(dir).export(DAY, List.of(row(1, "BIRYANI", "ऑर्डर-ü-🍛")));
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("ऑर्डर-ü-🍛"));
        assertFalse(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB, "no byte-order mark");
    }

    @Test
    void textCellsThatStartLikeAFormulaAreNeutralisedButMoneyAndNumbersAreNot() throws Exception {
        for (String dangerous : new String[] {"=cmd|' /C calc'!A0", "+1", "-1", "@SUM(A1)", "\tx", "\rx"}) {
            String cell = CsvReportExporter.text(dangerous);
            assertTrue(cell.startsWith("'") || cell.startsWith("\"'"), dangerous + " -> " + cell);
        }
        assertEquals("safe", CsvReportExporter.text("safe"));
        assertEquals("", CsvReportExporter.text(null));
        assertEquals("", CsvReportExporter.text(""));
        String csv = render(List.of(row(1, "BIRYANI", "=cmd")));
        assertTrue(csv.contains(",'=cmd,"), csv);
        assertTrue(csv.contains(",547.00,27.35,547.00,"), "money is untouched");
    }

    @Test
    void theSameRowsGiveTheSameBytes() throws Exception {
        List<ReportRow> rows = List.of(row(1, "BIRYANI", "a"), row(2, "BURGER", "b"));
        Path dir = AppFixture.scratchDirectory();
        CsvReportExporter exporter = new CsvReportExporter(dir);
        byte[] first = Files.readAllBytes(exporter.export(DAY, rows));
        byte[] second = Files.readAllBytes(exporter.export(DAY, rows));
        assertArrayEquals(first, second);
        assertEquals(render(rows), new String(first, StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyDayGivesAFileWithTheHeaderOnly() throws Exception {
        Path file = new CsvReportExporter(AppFixture.scratchDirectory()).export(DAY, List.of());
        assertEquals(HEADER, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("orders-2026-10-10.csv", file.getFileName().toString());
    }

    @Test
    void createsTheDirectoryReplacesAnExistingReportOfTheSameDayAndLeavesNoTemporaryFile() throws Exception {
        Path dir = AppFixture.scratchDirectory().resolve("nested").resolve("reports");
        CsvReportExporter exporter = new CsvReportExporter(dir);
        exporter.export(DAY, List.of(row(1, "BIRYANI", "old")));
        Path file = exporter.export(DAY, List.of(row(2, "BURGER", "new")));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("new") && !text.contains("old"), "the fresh export replaced the old one: " + text);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("orders-2026-10-10.csv"), files.map(p -> p.getFileName().toString()).toList(), "no .tmp left behind");
        }
    }

    @Test
    void aFailingMoveLeavesTheCauseTheOldReportAndNoTemporaryFile() throws Exception {
        Path dir = AppFixture.scratchDirectory();
        // the final name is a non-empty directory: the move cannot replace it, whatever the user id
        Path blocker = Files.createDirectories(dir.resolve("orders-2026-10-10.csv"));
        Files.writeString(blocker.resolve("keep"), "x");
        ReportExportException e = assertThrows(ReportExportException.class, () -> new CsvReportExporter(dir).export(DAY, List.of(row(1, "A", "b"))));
        assertEquals("REPORT_EXPORT", e.getCode());
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(Files.exists(blocker.resolve("keep")), "the existing path is untouched");
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("orders-2026-10-10.csv"), files.map(p -> p.getFileName().toString()).toList(), "the temporary file was removed");
        }
    }

    @Test
    void aDirectoryThatCannotBeCreatedIsReportedWithItsCause() throws Exception {
        Path blocker = Files.writeString(AppFixture.scratchDirectory().resolve("blocker"), "a file");
        ReportExportException e = assertThrows(ReportExportException.class, () -> new CsvReportExporter(blocker.resolve("reports")).export(DAY, List.of()));
        assertInstanceOf(IOException.class, e.getCause());
    }
}
