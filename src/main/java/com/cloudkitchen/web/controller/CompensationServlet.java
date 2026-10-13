package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.CompensationOperator;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET} and {@code POST /admin/compensations} (SDD 10.2 routes 28 and 29, 7.10.11, FR-WEB-14, ADMIN only). GET lists the
 * orders awaiting compensation (order, brand, reason, attempts, age, next attempt) with a Retry button each. POST
 * {@code action=retry} runs the one manual operation through {@link CompensationOperator}; the answer is always a redirect
 * (PRG) with a flash message: done (the order failed and its stock is back), already resolved (a second click, the sweeper
 * or a restart was first; nothing changed), or not completed this time (the attempt is recorded and the sweeper tries again).
 */
public class CompensationServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CompensationServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            request.setAttribute("pending", Views.of(services().compensation().pendingCompensations()));
        } catch (CloudKitchenException e) {
            throw new ServletException("The pending compensations could not be read", e);
        }
        request.setAttribute("pageTitle", "Compensations");
        render(request, response, "admin/compensations");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!"retry".equals(request.getParameter("action"))) {
            throw new BadRequestException("action");
        }
        long orderId = longParam(request, "orderId");
        try {
            CompensationOperator.Outcome outcome = services().compensation().retry(orderId, currentUser(request));
            if (outcome == CompensationOperator.Outcome.RESOLVED) {
                flashSuccess(request, "Compensation of order " + orderId + " is complete: the order has failed and its stock has been given back.");
            } else {
                flashInfo(request, "Order " + orderId + " is already resolved; nothing was changed.");
            }
        } catch (CloudKitchenException e) {
            LOG.warn("Manual compensation of order {} did not complete ({})", orderId, e.getCode());
            flashError(request, "The compensation of order " + orderId + " could not be completed this time. The attempt has been recorded; the system tries again by itself.");
        }
        redirect(request, response, "/admin/compensations");
    }
}
