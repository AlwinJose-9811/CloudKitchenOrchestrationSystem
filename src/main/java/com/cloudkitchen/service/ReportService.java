package com.cloudkitchen.service;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.dto.ReportRow;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.CsvReportExporter;
import com.cloudkitchen.util.TimeUtil;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The daily CSV report of the orders created on one UTC day (SDD 6, 8.2). The rows come from the database
 * ({@link OrderDao#findForReport}: id, creation time, source, brand, reference, status, units, money, payment), the
 * day is {@code [00:00:00Z, next day 00:00:00Z)} on {@code created_at}, and the file is written by
 * {@link CsvReportExporter}. Nothing is computed or filled in here.
 */
public final class ReportService {

    private final Database database;
    private final OrderDao orders;
    private final CsvReportExporter exporter;

    public ReportService(Database database, OrderDao orders, CsvReportExporter exporter) {
        this.database = Objects.requireNonNull(database, "database");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    /** The rows of the day, in order id order. */
    public List<ReportRow> rowsOf(LocalDate utcDay) throws CloudKitchenException {
        Objects.requireNonNull(utcDay, "utcDay");
        return database.withConnection(c -> orders.findForReport(c, TimeUtil.startOfUtcDay(utcDay), TimeUtil.endOfUtcDayExclusive(utcDay)));
    }

    /** Writes {@code orders-<day>.csv} into the configured report directory and returns the file. */
    public Path exportDaily(LocalDate utcDay) throws CloudKitchenException {
        return exporter.export(utcDay, rowsOf(utcDay));
    }

    /** Rows read from the database per query while a download is streamed. */
    public static final int STREAM_PAGE_SIZE = 500;

    /**
     * Streams the report of the day to {@code out} (HTTP download, D-078): the header, then the rows page by page in order id
     * order, each page read with its own pooled connection, so memory stays bounded and a slow client never holds a
     * connection. There is no row cap. The caller owns and closes the writer; it is flushed once at the end.
     */
    public void streamDaily(LocalDate utcDay, Writer out) throws CloudKitchenException, IOException {
        Objects.requireNonNull(utcDay, "utcDay");
        Instant from = TimeUtil.startOfUtcDay(utcDay);
        Instant to = TimeUtil.endOfUtcDayExclusive(utcDay);
        exporter.writeHeader(out);
        long after = 0;
        while (true) {
            long cursor = after;
            List<ReportRow> page = database.withConnection(c -> orders.findForReportAfter(c, from, to, cursor, STREAM_PAGE_SIZE));
            exporter.writeRows(out, page);
            if (page.size() < STREAM_PAGE_SIZE) {
                break;
            }
            after = page.get(page.size() - 1).orderId();
        }
        out.flush();
    }

    /** Writes the report of the day to {@code out} (HTTP download); the caller owns and closes the writer. */
    public void writeDaily(LocalDate utcDay, Writer out) throws CloudKitchenException, IOException {
        exporter.write(out, rowsOf(utcDay));
    }
}
