package com.cloudkitchen.web.controller;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.AuthenticationException;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.CsrfTokens;
import com.cloudkitchen.web.support.SessionKeys;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET} and {@code POST /login} (SDD 10.2 routes 2 and 3, 12.1 to 12.3, FR-AUT-02).
 *
 * <p>GET shows the form (the session and the CSRF token are created by {@link #render}); a visitor who is already logged
 * in is sent to the dashboard. POST checks the credentials with {@code AuthService.authenticate}, which locks the user row,
 * counts failures and answers every kind of refusal with the same exception. On success the session id is replaced with
 * {@code changeSessionId()} (session fixation), the CSRF token is rotated, the user is stored in the session and the
 * visitor is redirected to the dashboard; on failure the visitor is redirected back to the form with one generic
 * message. A password is never logged and never echoed; the form never keeps the username either.
 */
public class LoginServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(LoginServlet.class);
    /** Longer input cannot be a credential of this application; it is refused without hashing. */
    static final int MAX_FIELD = 256;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession existing = request.getSession(false);
        if (existing != null && existing.getAttribute(SessionKeys.USER) instanceof SessionUser) {
            redirect(request, response, "/dashboard");
            return;
        }
        request.setAttribute("pageTitle", "Sign in");
        render(request, response, "login");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        if (username == null || password == null || username.isBlank() || password.isEmpty()
                || username.length() > MAX_FIELD || password.length() > MAX_FIELD) {
            fail(request, response);
            return;
        }
        SessionUser user;
        try {
            user = services().auth().authenticate(username.trim(), password.toCharArray());
        } catch (AuthenticationException e) {
            fail(request, response);
            return;
        } catch (CloudKitchenException e) {
            LOG.error("Login could not be checked ({})", e.getCode());
            throw new ServletException("The login could not be checked", e);
        }
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        CsrfTokens.rotate(session);
        session.setAttribute(SessionKeys.USER, user);
        LOG.info("User {} logged in", user.userId());
        redirect(request, response, "/dashboard");
    }

    private void fail(HttpServletRequest request, HttpServletResponse response) throws IOException {
        flashError(request, AuthenticationException.GENERIC_MESSAGE);
        redirect(request, response, "/login");
    }
}
