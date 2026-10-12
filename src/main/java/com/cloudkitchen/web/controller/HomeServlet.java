package com.cloudkitchen.web.controller;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

/**
 * {@code GET /} (SDD 10.2 route 1): public; a logged-in session goes to the dashboard, everybody else to the login page.
 * It only looks at the session; the dashboard's own filters decide whether the session is still valid.
 */
public class HomeServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        boolean loggedIn = session != null && session.getAttribute(SessionKeys.USER) instanceof SessionUser;
        redirect(request, response, loggedIn ? "/dashboard" : "/login");
    }
}
