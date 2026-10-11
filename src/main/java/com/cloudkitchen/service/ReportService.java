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

    /** Writes the report of the day to {@code out} (HTTP download); the caller owns and closes the writer. */
    public void writeDaily(LocalDate utcDay, Writer out) throws CloudKitchenException, IOException {
        exporter.write(out, rowsOf(utcDay));
    }
}
