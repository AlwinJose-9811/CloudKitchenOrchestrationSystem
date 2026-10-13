package com.cloudkitchen.spike;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Phase 1 probe: declared first in web.xml, so it must run first. Records "first" in the X-Filter-Order header. */
public class SpikeOrderFilterFirst implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            http.setHeader("X-Filter-Order", "first");
        }
        chain.doFilter(request, response);
    }
}
