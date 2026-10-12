package com.cloudkitchen.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Second filter of the chain (SDD 10.3, FR-WEB-12). Every response carries {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY}, {@code Referrer-Policy: same-origin} and a restrictive
 * {@code Content-Security-Policy} (no script at all, styles and forms from this origin only). Everything except
 * {@code /static/*} is also {@code Cache-Control: no-store}: this filter runs before authentication and cannot tell
 * which pages are authenticated, so it protects all dynamic pages, which includes the authenticated ones.
 */
public class SecurityHeadersFilter implements Filter {

    static final String CSP = "default-src 'self'; style-src 'self'; script-src 'none'; frame-ancestors 'none'; form-action 'self'";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse out) {
            out.setHeader("X-Content-Type-Options", "nosniff");
            out.setHeader("X-Frame-Options", "DENY");
            out.setHeader("Referrer-Policy", "same-origin");
            out.setHeader("Content-Security-Policy", CSP);
            String path = http.getServletPath() == null ? "" : http.getServletPath();
            if (!path.startsWith("/static/")) {
                out.setHeader("Cache-Control", "no-store");
            }
        }
        chain.doFilter(request, response);
    }
}
