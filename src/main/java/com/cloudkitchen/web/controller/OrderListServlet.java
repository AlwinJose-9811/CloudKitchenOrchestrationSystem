package com.cloudkitchen.web.controller;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.dto.Page;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /orders} (SDD 10.2 route 6, FR-WEB-07): the paginated order list, newest first, 20 per page, filtered by
 * {@code status} and {@code source}. It shows no customer data. A malformed or out-of-range parameter is a 400. The
 * filter by {@code source} was already part of the Phase 3 demonstration this page replaces; the free-text, date and
 * search filters of the same route are COULD and not offered.
 */
public class OrderListServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    /** Far beyond any real page count and small enough that {@code (page - 1) * 20} cannot overflow an int. */
    static final int MAX_PAGE = 1_000_000;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        int pageNumber = intParam(request, "page", 1);
        if (pageNumber < 1 || pageNumber > MAX_PAGE) {
            throw new BadRequestException("page");
        }
        OrderStatus status = optionalEnumParam(request, "status", OrderStatus.class);
        OrderSource source = optionalEnumParam(request, "source", OrderSource.class);
        Page<OrderSummary> page;
        try {
            page = services().orderQueries().list(new OrderFilter(status, source, null, null, null), pageNumber);
        } catch (CloudKitchenException e) {
            throw new ServletException("The order list could not be read", e);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("items", page.items());
        view.put("count", page.items().size());
        view.put("page", page.page());
        view.put("total", page.total());
        view.put("totalPages", page.totalPages());
        request.setAttribute("listing", Views.of(view));
        request.setAttribute("selectedStatus", status == null ? "" : status.name());
        request.setAttribute("selectedSource", source == null ? "" : source.name());
        request.setAttribute("statuses", OrderStatus.values());
        request.setAttribute("sources", OrderSource.values());
        request.setAttribute("pageTitle", "Orders");
        render(request, response, "orders/list");
    }
}
