package com.cloudkitchen.web.filter;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.AuthService;
import com.cloudkitchen.web.support.AppServices;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.Flash;
import com.cloudkitchen.web.support.RequestPaths;
import com.cloudkitchen.web.support.RoutePolicy;
import com.cloudkitchen.web.support.SessionKeys;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fourth filter of the chain (SDD 10.3, 12.5, 12.10, FR-AUT-06, FR-AUT-10). A public route passes. Every other route (and
 * every path that is not in the table) needs a logged-in session; otherwise the answer is {@code 302 /login} with no
 * {@code returnTo} parameter and without opening a session.
 *
 * <p>The account is then checked against the database on <em>every</em> request, with no cache: a missing or disabled
 * account, or a different {@code session_version}, invalidates the session and sends the caller to {@code /login} with a
 * "please sign in again" message in a fresh session. The role used afterwards is the one just read from the database,
 * published as request attribute {@code ck.currentUser}. A database error fails closed: the request is not served and the
 * visitor sees the generic 500 page. Only the user id and a reason code are logged.
 */
public class AuthFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);
    static final String SIGN_IN_AGAIN = "Please sign in again.";

    private RoutePolicy policy;
    private ServletContext context;

    @Override
    public void init(FilterConfig config) {
        context = config.getServletContext();
        policy = RoutePolicy.from(context);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        RoutePolicy.Access access = policy.accessFor(RequestPaths.of(http), http.getMethod());
        if (access.known() && !access.authRequired()) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = http.getSession(false);
        SessionUser stored = session != null && session.getAttribute(SessionKeys.USER) instanceof SessionUser user ? user : null;
        if (stored == null) {
            out.sendRedirect(http.getContextPath() + "/login");
            return;
        }
        AppServices services = context.getAttribute(AppServices.ATTRIBUTE) instanceof AppServices s ? s : null;
        if (services == null) {
            LOG.error("Session check impossible: the application services are not available");
            out.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        AuthService auth = services.auth();
        Optional<SessionUser> fresh;
        try {
            fresh = auth.validateSession(stored);
        } catch (CloudKitchenException e) {
            LOG.error("Session check failed for user {} ({}); request refused", stored.userId(), e.getCode());
            out.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (fresh.isEmpty()) {
            LOG.info("Session rejected for user {}: account missing, disabled or session version changed", stored.userId());
            session.invalidate();
            Flash.add(http.getSession(true), Flash.Type.INFO, SIGN_IN_AGAIN);
            out.sendRedirect(http.getContextPath() + "/login");
            return;
        }
        http.setAttribute(BaseServlet.CURRENT_USER, fresh.get());
        chain.doFilter(request, response);
    }
}
