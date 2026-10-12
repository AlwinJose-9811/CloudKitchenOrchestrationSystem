package com.cloudkitchen.web.filter;

import com.cloudkitchen.web.support.CsrfTokens;
import com.cloudkitchen.web.support.RequestPaths;
import com.cloudkitchen.web.support.RoutePolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Third filter of the chain (SDD 10.3, 12.4, FR-WEB-03): the synchronizer token.
 *
 * <p>Every request other than {@code GET}, {@code HEAD} and {@code OPTIONS} must carry the session's token in the
 * parameter {@code _csrf}; a missing, wrong or foreign token (or no session at all) is answered with the generic 403 page,
 * whoever the caller is. This runs before authentication so that the login POST is protected too, which also means that an
 * anonymous POST without a valid token gets 403 and one with a valid token gets the redirect to {@code /login} from
 * {@code AuthFilter} (D-073). Only a {@link RoutePolicy.Tier#TEMPORARY} route is not checked; the production
 * table has none (the read-only Phase 3 demonstration was removed in C3), so only a test route can be exempt.
 *
 * <p>On a safe request to a route of the table the filter makes sure the session has a token, so that the page can put it
 * in its forms. It opens no session for {@code /health}, {@code /static/*}, {@code /error}, an unmapped path or a TEMPORARY
 * route (D-074): a probe, a stylesheet or a scanner must not allocate server memory. Token values are never logged.
 */
public class CsrfFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(CsrfFilter.class);

    private RoutePolicy policy;

    @Override
    public void init(FilterConfig config) {
        policy = RoutePolicy.from(config.getServletContext());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        String method = http.getMethod().toUpperCase(Locale.ROOT);
        String path = RequestPaths.of(http);
        boolean safe = method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
        if (safe) {
            if (needsToken(path, method)) {
                CsrfTokens.ensure(http.getSession(true));
            }
            chain.doFilter(request, response);
            return;
        }
        if (policy.find(path, method).map(RoutePolicy.Route::csrfExempt).orElse(false)) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = http.getSession(false);
        if (!CsrfTokens.matches(session, http.getParameter(CsrfTokens.PARAMETER))) {
            LOG.warn("CSRF check failed: method={} path={} session={}", method, RequestPaths.printable(path), session == null ? "none" : "present");
            out.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean needsToken(String path, String method) {
        if (policy.createsNoSession(path, method) || "/error".equals(path)) {
            return false;
        }
        return policy.find(path, method).map(route -> !route.csrfExempt()).orElse(false);
    }
}
