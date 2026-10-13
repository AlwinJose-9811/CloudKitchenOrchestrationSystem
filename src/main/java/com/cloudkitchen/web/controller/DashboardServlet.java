package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * {@code GET /dashboard} (SDD 10.2 route 5, FR-WEB-06): order counts by status, queue size and capacity per station, worker
 * state with generation and restarts, metrics, low-stock items, simulator state, the recovery banner and the count and age of
 * orders awaiting compensation. The page asks the browser to reload itself every five seconds
 * ({@code <meta http-equiv="refresh">}; the content security policy allows no script). The data is one
 * {@code DashboardService.snapshot()}; nothing is computed here.
 */
public class DashboardServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    static final int REFRESH_SECONDS = 5;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            request.setAttribute("view", Views.of(services().dashboard().snapshot()));
        } catch (CloudKitchenException e) {
            throw new ServletException("The dashboard could not be read", e);
        }
        request.setAttribute("pageTitle", "Dashboard");
        request.setAttribute("refreshSeconds", REFRESH_SECONDS);
        render(request, response, "dashboard");
    }
}
