package com.cloudkitchen.web.controller;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.dto.Page;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.OrderQueryService;
import com.cloudkitchen.web.listener.AppBootstrapListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 demonstration of the whole path Tomcat -> servlet -> service -> DAO -> embedded database: a read-only list
 * of the persisted orders. It carries no customer data, has no write action and is public only because the login
 * flow does not exist yet; Phase 7 replaces it with the authenticated {@code /orders} list. Only {@code GET} is
 * implemented, so every other method answers 405.
 */
public class OrdersDemoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(OrdersDemoServlet.class);
    private static final String VIEW = "/WEB-INF/views/demo/orders.jsp";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        OrderQueryService queries = (OrderQueryService) getServletContext().getAttribute(AppBootstrapListener.ORDER_QUERIES_ATTRIBUTE);
        if (queries == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        int pageNumber;
        OrderStatus status;
        OrderSource source;
        try {
            pageNumber = intParameter(request.getParameter("page"), 1);
            status = enumParameter(request.getParameter("status"), OrderStatus.class);
            source = enumParameter(request.getParameter("source"), OrderSource.class);
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        Page<OrderSummary> page;
        try {
            page = queries.list(new OrderFilter(status, source, null, null, null), pageNumber);
        } catch (CloudKitchenException e) {
            LOG.error("The order list could not be read ({}): {}", e.getCode(), e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        request.setAttribute("page", page);
        request.setAttribute("selectedStatus", status == null ? "" : status.name());
        request.setAttribute("selectedSource", source == null ? "" : source.name());
        request.setAttribute("statuses", OrderStatus.values());
        request.setAttribute("sources", OrderSource.values());
        response.setHeader("Cache-Control", "no-store");
        request.getRequestDispatcher(VIEW).forward(request, response);
    }

    private static int intParameter(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if (raw.length() > 9) {
            throw new IllegalArgumentException("number too long");
        }
        int value = Integer.parseInt(raw.strip());
        if (value < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumParameter(String raw, Class<E> type) {
        return raw == null || raw.isBlank() ? null : Enum.valueOf(type, raw.strip());
    }
}
