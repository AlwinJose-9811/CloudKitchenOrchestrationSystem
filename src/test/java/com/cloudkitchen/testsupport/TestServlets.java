package com.cloudkitchen.testsupport;

import com.cloudkitchen.web.support.BaseServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/**
 * Servlets that exist only in tests, registered on the context by {@link TestApp} before Tomcat starts (they are not in
 * {@code web.xml}). They force the situations the production pages must survive: an uncaught exception, any error status,
 * a malformed parameter, a flash message, a rendered view, a form echo and a session.
 */
public final class TestServlets {

    /** The text that must never reach a visitor: it looks like a stack frame and like SQL. */
    public static final String SECRET_DETAIL = "SELECT * FROM staff_users at com.cloudkitchen.secret.Internal";

    private TestServlets() {
    }

    static void register(Context context) {
        add(context, "TestBoom", new Boom(), "/__test/boom");
        add(context, "TestStatus", new Status(), "/__test/status");
        add(context, "TestParams", new Params(), "/__test/params");
        add(context, "TestFlashAdd", new FlashAdd(), "/__test/flash-add");
        add(context, "TestRender", new Render(), "/__test/render");
        add(context, "TestEcho", new Echo(), "/__test/echo");
        add(context, "TestSession", new SessionStarter(), "/__test/session");
    }

    private static void add(Context context, String name, HttpServlet servlet, String pattern) {
        Tomcat.addServlet(context, name, servlet);
        context.addServletMappingDecoded(pattern, name);
    }

    /** Throws an uncaught exception whose message carries {@link #SECRET_DETAIL}. */
    public static final class Boom extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            throw new IllegalStateException(SECRET_DETAIL);
        }
    }

    /** {@code ?code=NNN} answers {@code sendError(NNN)}. */
    public static final class Status extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.sendError(Integer.parseInt(request.getParameter("code")), SECRET_DETAIL);
        }
    }

    /** Parses {@code n} (int), {@code id} (long) and {@code kind} (enum) with the {@link BaseServlet} helpers. */
    public static final class Params extends BaseServlet {
        private static final long serialVersionUID = 1L;

        enum Kind { A, B }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            int n = intParam(request, "n", 7);
            long id = request.getParameter("id") == null ? 0 : longParam(request, "id");
            Kind kind = optionalEnumParam(request, "kind", Kind.class);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().print("n=" + n + " id=" + id + " kind=" + kind);
        }
    }

    /** Stores a flash message (and a session) and redirects to the render servlet, as every POST handler does. */
    public static final class FlashAdd extends BaseServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            flashSuccess(request, request.getParameter("text") == null ? "Saved." : request.getParameter("text"));
            flashError(request, "Second message");
            redirect(request, response, "/__test/render");
        }
    }

    /**
     * Renders the 500 view through {@code BaseServlet.render}, which shows the pending flash messages. With
     * {@code role=ADMIN|KITCHEN_STAFF} (and {@code name}) it plays the part of {@code AuthFilter} and sets the current user, so that
     * the navigation and the logout form of the layout are rendered.
     */
    public static final class Render extends BaseServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
            String role = request.getParameter("role");
            if (role != null) {
                String name = request.getParameter("name") == null ? "tester" : request.getParameter("name");
                request.setAttribute(CURRENT_USER, new com.cloudkitchen.dto.SessionUser(1L, name, com.cloudkitchen.domain.enums.Role.valueOf(role), 0));
            }
            request.setAttribute("statusCode", 200);
            request.setAttribute("errorId", "rendered");
            render(request, response, "error/500");
        }
    }

    /** Echoes the form field {@code name} as UTF-8 text, to prove the request decoding. */
    public static final class Echo extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getOutputStream().write(("name=" + request.getParameter("name")).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Creates an HTTP session, so that the cookie attributes can be inspected. */
    public static final class SessionStarter extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().print("maxInactive=" + session.getMaxInactiveInterval());
        }
    }
}
