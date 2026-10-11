package com.cloudkitchen.io;

import com.cloudkitchen.dto.ReportRow;
import com.cloudkitchen.exception.ReportExportException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Writes the daily order report as CSV (SDD 8.2, FR-FIL-03).
 *
 * <p><b>Format.</b> UTF-8 without a byte-order mark; header row, then one row per order in the order given (the query
 * sorts by order id), every line ended by CRLF (RFC 4180); columns
 * {@code order_id,created_at_utc,source,brand,external_ref,status,item_count,subtotal,platform_fee,total,payment_mode,payment_status};
 * {@code created_at_utc} is ISO-8601 UTC with milliseconds ({@code 2026-10-10T06:41:12.345Z}); money is a plain decimal
 * with two places; {@code item_count} is the number of units ordered. A field is quoted if it contains a comma, a
 * quote, CR or LF, and inner quotes are doubled. A text field that starts with {@code =}, {@code +}, {@code -},
 * {@code @}, tab or CR is prefixed with an apostrophe so that a spreadsheet does not run it as a formula. There is no
 * customer column. The same rows always produce the same bytes.
 *
 * <p><b>Files.</b> {@link #export} writes {@code orders-<yyyy-MM-dd>.csv} (the UTC day) into the configured
 * directory, creating it if needed. The content is written to a temporary file in the same directory, forced to disk
 * and moved over the final name with {@code ATOMIC_MOVE}; where the file system cannot move atomically it falls back
 * to a plain replacing move (a reader could then briefly see the old or the new file, never a half-written one,
 * because the temporary file is complete before the move). An existing report of the same day is replaced by the
 * fresh export; an empty day produces a file with the header only. On any failure the temporary file is deleted, the
 * previous final file (if any) is untouched, and a {@link ReportExportException} with the cause is thrown.
 */
public final class CsvReportExporter {

    static final String HEADER = "order_id,created_at_utc,source,brand,external_ref,status,item_count,subtotal,platform_fee,total,payment_mode,payment_status";
    private static final String EOL = "\r\n";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Path directory;

    public CsvReportExporter(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /** The file name of the report of one UTC day. */
    public static String fileName(LocalDate utcDay) {
        return "orders-" + Objects.requireNonNull(utcDay, "utcDay") + ".csv";
    }

    /** Writes the report of {@code utcDay} atomically and returns the final file. */
    public Path export(LocalDate utcDay, List<ReportRow> rows) throws ReportExportException {
        Objects.requireNonNull(rows, "rows");
        byte[] content;
        try {
            StringWriter text = new StringWriter();
            write(text, rows);
            content = text.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ReportExportException("The report could not be formatted", e);
        }
        Path target = directory.resolve(fileName(utcDay));
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, "orders-" + utcDay + "-", ".tmp");
            Files.write(temp, content);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            return target;
        } catch (IOException | RuntimeException e) {
            throw new ReportExportException("The report " + target + " could not be written (" + e.getClass().getSimpleName() + ")", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // nothing more can be done for a temporary file; the failure above is the one that is reported
                }
            }
        }
    }

    /** Writes the header and the rows to {@code out} (used for the file and for an HTTP download); does not close it. */
    public void write(Writer out, List<ReportRow> rows) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(rows, "rows");
        out.write(HEADER);
        out.write(EOL);
        for (ReportRow row : rows) {
            out.write(line(row));
            out.write(EOL);
        }
        out.flush();
    }

    private static String line(ReportRow r) {
        return String.join(",",
                Long.toString(r.orderId()),
                text(TS.format(r.createdAtUtc())),
                text(r.source().name()),
                text(r.brand()),
                text(r.externalRef()),
                text(r.status().name()),
                Integer.toString(r.itemCount()),
                money(r.subtotal()),
                money(r.platformFee()),
                money(r.total()),
                text(r.paymentMode().name()),
                text(r.paymentStatus().name()));
    }

    private static String money(java.math.BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /** A text cell: formula characters neutralised, then quoted if it needs it. */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String cell = value;
        char first = cell.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            cell = "'" + cell;
        }
        boolean quote = false;
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                quote = true;
                break;
            }
        }
        return quote ? '"' + cell.replace("\"", "\"\"") + '"' : cell;
    }
}
