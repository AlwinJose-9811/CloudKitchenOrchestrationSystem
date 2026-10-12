package com.cloudkitchen.web.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Target of every {@code error-page} of {@code web.xml} (SDD 10.1, FR-WEB-05, D-077): an error status, an uncaught
 * exception or a {@code sendError}. The detail goes to the server log under an eight-hex-digit error id; the visitor
 * sees a generic page for 400, 403, 404, 405 or 500 (any other status uses the 500 view) with that id and nothing else:
 * no exception name, no stack frame, no SQL, no class or server name.
 *
 * <p>It answers every HTTP method (an error page is requested with the method of the failed request) and extends
 * {@link HttpServlet} directly, not {@code BaseServlet}, whose final {@code service} is meant for controllers.
 */
public class ErrorServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ErrorServlet.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Throwable failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable t ? t : null;
        Integer attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer i ? i : null;
        int status = attribute != null ? attribute : failure != null ? 500 : 404; // /error requested directly
        String errorId = HexFormat.of().formatHex(nextBytes());
        String path = printable(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri ? uri : request.getRequestURI());
        if (failure != null || status >= 500) {
            LOG.error("Request failed: errorId={} status={} path={}", errorId, status, path, failure);
        } else {
            LOG.info("Request refused: errorId={} status={} path={}", errorId, status, path);
        }
        if (response.isCommitted()) {
            return;
        }
        if (attribute == null) {
            response.setStatus(status);
        }
        request.setAttribute("statusCode", status);
        request.setAttribute("errorId", errorId);
        response.setContentType("text/html;charset=UTF-8");
        request.getRequestDispatcher("/WEB-INF/views/error/" + view(status) + ".jsp").forward(request, response);
    }

    static String view(int status) {
        return switch (status) {
            case 400, 403, 404, 405 -> Integer.toString(status);
            default -> "500";
        };
    }

    private static byte[] nextBytes() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** The request path as it appears in the log: no control characters (log injection), at most 200 characters. */
    private static String printable(String value) {
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
