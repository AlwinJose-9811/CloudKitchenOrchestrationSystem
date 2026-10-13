package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.CsvReportExporter;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.FormInput;
import com.cloudkitchen.web.support.RequestPaths;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET /admin/reports} (the date picker) and {@code GET /admin/reports/daily.csv?date=yyyy-MM-dd} (the download),
 * SDD 10.2 routes 21 and 22, FR-FIL-03, ADMIN only. The date must be a real calendar day in exactly that form, otherwise 400.
 * The file name is built from the parsed date, never from the request text. The body is streamed page by page to the
 * response writer (D-078); if the database fails before anything was sent the answer is the generic 500 page, if it fails
 * later the connection is aborted, so a truncated file is never presented as complete.
 */
public class ReportServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ReportServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (RequestPaths.of(request).endsWith("/daily.csv")) {
            download(request, response);
            return;
        }
        request.setAttribute("today", LocalDate.now(services().clock().withZone(ZoneOffset.UTC)).toString());
        request.setAttribute("pageTitle", "Reports");
        render(request, response, "admin/reports");
    }

    private void download(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        LocalDate day = FormInput.isoDate(request.getParameter("date"));
        if (day == null) {
            throw new BadRequestException("date");
        }
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + CsvReportExporter.fileName(day) + "\"");
        response.setHeader("Cache-Control", "no-store");
        try {
            PrintWriter out = response.getWriter();
            services().reports().streamDaily(day, out);
            if (out.checkError()) {
                throw new IOException("The client closed the connection during the download");
            }
            LOG.info("Report of {} downloaded by user {}", day, currentUser(request).userId());
        } catch (CloudKitchenException e) {
            LOG.error("The report of {} could not be read ({})", day, e.getCode());
            if (!response.isCommitted()) {
                response.reset();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
            throw new IOException("The report was cut short: the database failed after the download had started (" + e.getCode() + ")", e);
        }
    }
}
