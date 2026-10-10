package com.cloudkitchen.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Phase 1 probe: declared second in web.xml. Appends "second" to the X-Filter-Order header set by the first. */
public class SpikeOrderFilterSecond implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            String previous = http.getHeader("X-Filter-Order");
            http.setHeader("X-Filter-Order", previous == null ? "second" : previous + ",second");
        }
        chain.doFilter(request, response);
    }
}
