package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.ValidationException;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.FormInput;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET} and {@code POST /admin/menu} (SDD 10.2 routes 12 and 13, FR-INV-08, ADMIN only): list every menu item, create
 * one (TX6, with its inventory row) and change the price and the active flag of one. An action other than {@code create} and
 * {@code update} and a malformed item id are a 400; a price or a number from a person that is not in the exact form, and every
 * rule the service enforces, are answered with a flash message and no change. {@code active} is a checkbox: only the text
 * {@code true} means active.
 */
public class AdminMenuServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(AdminMenuServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            request.setAttribute("items", Views.of(services().menu().listRows()));
            request.setAttribute("brands", Views.of(services().menu().activeBrands()));
        } catch (CloudKitchenException e) {
            throw new ServletException("The menu could not be read (" + e.getCode() + ")", e);
        }
        request.setAttribute("pageTitle", "Menu");
        render(request, response, "admin/menu");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        if (!"create".equals(action) && !"update".equals(action)) {
            throw new BadRequestException("action");
        }
        if ("create".equals(action)) {
            create(request);
        } else {
            update(request);
        }
        redirect(request, response, "/admin/menu");
    }

    private void create(HttpServletRequest request) {
        BigDecimal price = FormInput.decimal(request.getParameter("price"));
        Integer initialStock = FormInput.wholeNumber(request.getParameter("initialStock"));
        Integer reorderLevel = FormInput.wholeNumber(request.getParameter("reorderLevel"));
        if (price == null || initialStock == null || reorderLevel == null) {
            flashError(request, "The price must be a number such as 149.00; the initial stock and the reorder level must be whole numbers.");
            return;
        }
        try {
            long id = services().menu().create(request.getParameter("stationCode"), request.getParameter("name"), price, initialStock, reorderLevel);
            LOG.info("Menu item {} created by user {}", id, currentUser(request).userId());
            flashSuccess(request, "The item was added to the menu (item " + id + ").");
        } catch (ValidationException e) {
            e.getErrors().forEach(error -> flashError(request, error.message()));
        } catch (CloudKitchenException e) {
            LOG.warn("Menu item creation failed ({})", e.getCode());
            flashError(request, "The item could not be added right now. Nothing was changed; try again.");
        }
    }

    private void update(HttpServletRequest request) {
        long itemId = longParam(request, "itemId");
        BigDecimal price = FormInput.decimal(request.getParameter("price"));
        if (price == null) {
            flashError(request, "The price must be a number such as 149.00.");
            return;
        }
        boolean active = "true".equals(request.getParameter("active"));
        try {
            if (services().menu().update(itemId, price, active)) {
                LOG.info("Menu item {} changed by user {}", itemId, currentUser(request).userId());
                flashSuccess(request, "Item " + itemId + " is now " + (active ? "active" : "inactive") + " at " + price.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ".");
            } else {
                flashError(request, "This item does not exist.");
            }
        } catch (ValidationException e) {
            e.getErrors().forEach(error -> flashError(request, error.message()));
        } catch (CloudKitchenException e) {
            LOG.warn("Menu item update failed ({})", e.getCode());
            flashError(request, "The item could not be changed right now. Nothing was changed; try again.");
        }
    }
}
