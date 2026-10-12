package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** {@code GET /inventory} (SDD 10.2 route 11, FR-INV-07): stock and reorder level per item; low stock is flagged with the text "LOW". */
public class InventoryServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            request.setAttribute("items", Views.of(services().stock().listAll()));
        } catch (CloudKitchenException e) {
            throw new ServletException("The stock could not be read", e);
        }
        request.setAttribute("pageTitle", "Inventory");
        render(request, response, "inventory/list");
    }
}
