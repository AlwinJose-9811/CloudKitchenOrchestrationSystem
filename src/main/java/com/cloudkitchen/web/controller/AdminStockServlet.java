package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.ValidationException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.FormInput;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET} and {@code POST /admin/stock} (SDD 10.2 routes 14 and 15, FR-INV-09, ADMIN only): list the stock and replace the
 * quantity and the reorder level of one item (TX5). The item id comes from the page, so a malformed one is a 400; the two
 * numbers come from a person, so text that is not a whole number, a negative number and an unknown item are answered with a
 * flash message and no change.
 */
public class AdminStockServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(AdminStockServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            request.setAttribute("items", Views.of(services().stock().listAll()));
        } catch (CloudKitchenException e) {
            throw new ServletException("The stock could not be read (" + e.getCode() + ")", e);
        }
        request.setAttribute("pageTitle", "Stock");
        render(request, response, "admin/stock");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        long itemId = longParam(request, "itemId");
        Integer stockQty = FormInput.wholeNumber(request.getParameter("stockQty"));
        Integer reorderLevel = FormInput.wholeNumber(request.getParameter("reorderLevel"));
        if (stockQty == null || reorderLevel == null) {
            flashError(request, "The stock and the reorder level must be whole numbers.");
            redirect(request, response, "/admin/stock");
            return;
        }
        try {
            services().stock().adjust(itemId, stockQty, reorderLevel);
            LOG.info("Stock of item {} set by user {}", itemId, currentUser(request).userId());
            flashSuccess(request, "The stock of item " + itemId + " is now " + stockQty + " with reorder level " + reorderLevel + ".");
        } catch (ValidationException e) {
            e.getErrors().forEach(error -> flashError(request, error.message()));
        } catch (CloudKitchenException e) {
            LOG.warn("Stock adjustment failed ({})", e.getCode());
            flashError(request, "The stock could not be changed right now. Nothing was changed; try again.");
        }
        redirect(request, response, "/admin/stock");
    }
}
