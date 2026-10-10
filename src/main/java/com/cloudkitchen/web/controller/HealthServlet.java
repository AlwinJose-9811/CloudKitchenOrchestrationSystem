package com.cloudkitchen.web.controller;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Liveness probe: always public, no session, no database access. Answers 200 with the exact body {@code OK}. */
public class HealthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final byte[] BODY = "OK".getBytes(StandardCharsets.UTF_8);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }
}
