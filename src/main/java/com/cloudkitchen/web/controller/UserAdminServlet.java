package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET} and {@code POST /admin/users} (SDD 10.2 routes 23 and 24, FR-AUT-11, ADMIN only): list the accounts and
 * enable or disable one (TX10). The service refuses to disable the acting administrator and the last enabled
 * administrator; those refusals, an unknown account and a database failure are answered with a flash message. The COULD
 * action {@code reset} is not implemented, so it is a 400 like any other unknown action. A disabled account loses its
 * sessions at its next request because {@code AuthFilter} re-reads {@code enabled} and {@code session_version}.
 */
public class UserAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(UserAdminServlet.class);
    private static final Set<String> SAFE_REFUSALS = Set.of("USER_NOT_FOUND", "SELF_DISABLE_REFUSED", "LAST_ADMIN_REFUSED");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            request.setAttribute("users", Views.of(services().auth().listUsers()));
        } catch (CloudKitchenException e) {
            throw new ServletException("The accounts could not be read (" + e.getCode() + ")", e);
        }
        request.setAttribute("pageTitle", "Users");
        render(request, response, "admin/users");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        if (!"disable".equals(action) && !"enable".equals(action)) {
            throw new BadRequestException("action");
        }
        long targetId = longParam(request, "userId");
        boolean enable = "enable".equals(action);
        long actorId = currentUser(request).userId();
        try {
            services().auth().setEnabled(actorId, targetId, enable);
            LOG.info("Account {} {} by user {}", targetId, enable ? "enabled" : "disabled", actorId);
            flashSuccess(request, "Account " + targetId + " is now " + (enable ? "enabled." : "disabled; its sessions end at its next request."));
        } catch (CloudKitchenException e) {
            if (SAFE_REFUSALS.contains(e.getCode())) {
                flashError(request, e.getMessage());
            } else {
                LOG.warn("Changing account {} failed ({})", targetId, e.getCode());
                flashError(request, "The account could not be changed right now. Nothing was changed; try again.");
            }
        }
        redirect(request, response, "/admin/users");
    }
}
