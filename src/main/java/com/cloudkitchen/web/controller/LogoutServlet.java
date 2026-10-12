package com.cloudkitchen.web.controller;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /logout} (SDD 10.2 route 4, FR-AUT-04). The CSRF and login checks have already passed. The whole session is
 * invalidated, so the old cookie grants nothing; the confirmation message lives in a new, empty session that carries no user.
 */
public class LogoutServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(LogoutServlet.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            if (session.getAttribute(SessionKeys.USER) instanceof SessionUser user) {
                LOG.info("User {} logged out", user.userId());
            }
            session.invalidate();
        }
        flashInfo(request, "You have been signed out.");
        redirect(request, response, "/login");
    }
}
