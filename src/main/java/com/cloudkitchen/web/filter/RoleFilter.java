package com.cloudkitchen.web.filter;

import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.web.support.BaseServlet;
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
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fifth filter of the chain (SDD 10.3, 12.5, FR-AUT-06): the role check. It compares the role that {@code AuthFilter} just
 * read from the database ({@code ck.currentUser}, never the role cached in the session) with the roles the route table
 * allows; a mismatch is the generic 403. A route that is not in the table is ADMIN-only. If a protected request somehow
 * arrives without the user attribute (the filter order was changed) it is refused, not served.
 */
public class RoleFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(RoleFilter.class);

    private RoutePolicy policy;

    @Override
    public void init(FilterConfig config) {
        policy = RoutePolicy.from(config.getServletContext());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        String path = RequestPaths.of(http);
        RoutePolicy.Access access = policy.accessFor(path, http.getMethod());
        if (access.known() && !access.authRequired()) {
            chain.doFilter(request, response);
            return;
        }
        if (!(http.getAttribute(BaseServlet.CURRENT_USER) instanceof SessionUser user)) {
            LOG.error("Role check without a validated user on a protected route; request refused");
            out.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (!access.allows(user.role())) {
            LOG.warn("Access denied: user={} role={} method={} path={}", user.userId(), user.role(), http.getMethod(), RequestPaths.printable(path));
            out.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
