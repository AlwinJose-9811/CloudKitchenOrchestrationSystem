package com.cloudkitchen.web.support;

import jakarta.servlet.http.HttpServletRequest;

/** The path of a request the way the container mapped it, so that the filters and the servlet mapping agree. */
public final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * Servlet path plus path info, relative to the context: decoded and normalised by the container (no {@code ;jsessionid},
     * no {@code /./}, no encoded dots), which the raw request URI is not. The root URL is {@code /}.
     */
    public static String of(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = servletPath + pathInfo;
        return path.isEmpty() ? "/" : path;
    }

    /** A path as it may appear in a log line: control characters replaced, at most 200 characters (log injection). */
    public static String printable(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), 200));
        for (int i = 0; i < value.length() && i < 200; i++) {
            char c = value.charAt(i);
            out.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return out.toString();
    }
}
