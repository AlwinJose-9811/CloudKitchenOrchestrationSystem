package com.cloudkitchen.web.controller;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.OrderService.CancelOutcome;
import com.cloudkitchen.web.support.BaseServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /orders/cancel} (SDD 10.2 route 10, FR-LIF-05 UI, RR-7). The CSRF, login and role checks have already passed.
 * The servlet only reads the order id and the signed-in user and calls {@code OrderService.cancelOrderDetailed}, which
 * re-reads the status and the compensation marker, refuses an order that is awaiting compensation or whose status does not
 * allow {@code CANCELLED} (the lifecycle rule lives in {@code OrderStatus}), performs the conditional transition and gives
 * the stock back when the rules say so. Every outcome ends in a redirect (PRG) with one flash message: back to the order
 * page, or to the list if there is no such order. The history actor is {@code user:<username>}.
 */
public class OrderCancelServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(OrderCancelServlet.class);
    private static final int MAX_ACTOR = 50;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        long id = longParam(request, "id");
        SessionUser user = currentUser(request);
        String actor = ("user:" + user.username());
        actor = actor.length() > MAX_ACTOR ? actor.substring(0, MAX_ACTOR) : actor;
        CancelOutcome outcome;
        try {
            outcome = services().orders().cancelOrderDetailed(id, actor);
        } catch (CloudKitchenException e) {
            LOG.error("Cancelling order {} failed ({})", id, e.getCode());
            throw new ServletException("The order could not be cancelled", e);
        }
        String page = "/orders/view?id=" + id;
        switch (outcome) {
            case CANCELLED -> {
                flashSuccess(request, "Order " + id + " has been cancelled.");
                redirect(request, response, page);
            }
            case AWAITING_COMPENSATION -> {
                flashError(request, "Order " + id + " is awaiting compensation and cannot be cancelled here; an administrator resolves it.");
                redirect(request, response, page);
            }
            case NOT_CANCELLABLE -> {
                flashError(request, "Order " + id + " can no longer be cancelled.");
                redirect(request, response, page);
            }
            case CONTENDED -> {
                flashError(request, "Order " + id + " was changed by someone else at the same moment; please look at it and try again.");
                redirect(request, response, page);
            }
            case NOT_FOUND -> {
                flashError(request, "There is no order " + id + ".");
                redirect(request, response, "/orders");
            }
        }
    }
}
