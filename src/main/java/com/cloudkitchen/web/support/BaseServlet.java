package com.cloudkitchen.web.support;

import com.cloudkitchen.dto.SessionUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;

/**
 * The base of every controller (SDD 10.1): access to the services, rendering a view, redirecting, flash messages and
 * parameter parsing that answers a malformed value with the generic 400 page. Controllers only read the request, call a
 * service and choose the response; they hold no business logic and every POST ends in {@link #redirect} (PRG).
 *
 * <p>Views are {@code /WEB-INF/views/<name>.jsp}. {@link #render} puts the pending flash messages
 * ({@code flashes}), the CSRF token ({@code csrfToken}) and the current user ({@code ck.currentUser}, set by
 * {@code AuthFilter}) where the layout and the {@code csrf} tag expect them. {@link #service} is final so that no
 * controller can bypass the 400 mapping; any other exception reaches the container, which forwards to {@code /error}.
 */
public abstract class BaseServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    /** Request attribute holding the {@link SessionUser} that {@code AuthFilter} validated for this request. */
    public static final String CURRENT_USER = "ck.currentUser";

    @Override
    protected final void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            super.service(request, response);
        } catch (BadRequestException e) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            }
        }
    }

    /** The services of the running application; if there are none the request ends in the generic 500 page. */
    protected final AppServices services() {
        Object value = getServletContext().getAttribute(AppServices.ATTRIBUTE);
        if (value instanceof AppServices services) {
            return services;
        }
        throw new IllegalStateException("The application services are not available");
    }

    /** The user validated for this request, or {@code null} on a public route. */
    protected final SessionUser currentUser(HttpServletRequest request) {
        return request.getAttribute(CURRENT_USER) instanceof SessionUser user ? user : null;
    }

    /** Forwards to {@code /WEB-INF/views/<view>.jsp} after placing the flash messages and the CSRF token in the request. */
    protected final void render(HttpServletRequest request, HttpServletResponse response, String view) throws ServletException, IOException {
        HttpSession session = request.getSession(true);
        request.setAttribute("flashes", Flash.consume(session));
        request.setAttribute("csrfToken", CsrfTokens.ensure(session));
        response.setContentType("text/html;charset=UTF-8");
        request.getRequestDispatcher("/WEB-INF/views/" + view + ".jsp").forward(request, response);
    }

    /** {@code 302} to {@code contextPath + path}; the path is always one of our own routes, never request input. */
    protected final void redirect(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        response.sendRedirect(request.getContextPath() + path);
    }

    protected final void flashSuccess(HttpServletRequest request, String message) {
        Flash.add(request.getSession(true), Flash.Type.SUCCESS, message);
    }

    protected final void flashInfo(HttpServletRequest request, String message) {
        Flash.add(request.getSession(true), Flash.Type.INFO, message);
    }

    protected final void flashError(HttpServletRequest request, String message) {
        Flash.add(request.getSession(true), Flash.Type.ERROR, message);
    }

    /** A required integer parameter; missing or malformed gives 400. */
    protected static int intParam(HttpServletRequest request, String name) {
        try {
            return Integer.parseInt(required(request, name));
        } catch (NumberFormatException e) {
            throw new BadRequestException(name);
        }
    }

    /** An optional integer parameter; absent or empty gives {@code defaultValue}, malformed gives 400. */
    protected static int intParam(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        return value == null || value.isBlank() ? defaultValue : intParam(request, name);
    }

    /** A required long parameter; missing or malformed gives 400. */
    protected static long longParam(HttpServletRequest request, String name) {
        try {
            return Long.parseLong(required(request, name));
        } catch (NumberFormatException e) {
            throw new BadRequestException(name);
        }
    }

    /** A required enum parameter (exact constant name); missing or unknown gives 400. */
    protected static <E extends Enum<E>> E enumParam(HttpServletRequest request, String name, Class<E> type) {
        try {
            return Enum.valueOf(type, required(request, name).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(name);
        }
    }

    /** An optional enum parameter; absent or empty gives {@code null}, unknown gives 400. */
    protected static <E extends Enum<E>> E optionalEnumParam(HttpServletRequest request, String name, Class<E> type) {
        String value = request.getParameter(name);
        return value == null || value.isBlank() ? null : enumParam(request, name, type);
    }

    private static String required(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name);
        }
        return value.trim();
    }
}
