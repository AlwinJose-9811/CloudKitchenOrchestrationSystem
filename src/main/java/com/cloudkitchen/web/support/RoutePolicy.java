package com.cloudkitchen.web.support;

import com.cloudkitchen.domain.enums.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import jakarta.servlet.ServletContext;

/**
 * The single route table of SDD 10.2 (numbers are the row numbers there). {@code AuthFilter}, {@code RoleFilter},
 * {@code CsrfFilter} and the access-matrix tests all read it, so a route cannot be protected in one place and open in
 * another. Paths are relative to the context path. {@code HEAD} is treated as {@code GET}. A path and method that are
 * not in the table are protected and ADMIN-only (deny by default, SDD 12.5); the container still answers 404 for a
 * path that has no servlet. The COULD route {@code /track} (row 25) is not in the table until it is implemented.
 *
 * <p>The filters read the instance published in the servlet-context attribute {@value #ATTRIBUTE}, or {@link #standard()}
 * when there is none. {@link #withRoutes} exists so that a test can add its own routes next to the real ones; nothing in
 * production calls it.
 */
public final class RoutePolicy {

    /** Servlet-context attribute under which a non-standard policy (tests only) is published. */
    public static final String ATTRIBUTE = "ck.routePolicy";

    /**
     * The scope tier of a route (SDD 10.2). {@code TEMPORARY} marks a public route that has no row in the SDD table and
     * that {@code CsrfFilter} does not check; the production table has none (the Phase 3 demonstration {@code /demo/*}
     * was removed in checkpoint C3, D-076, D-085). Only a test may add one through {@link #withRoutes}.
     */
    public enum Tier { MUST, SHOULD, COULD, TEMPORARY }

    /**
     * One row of the table. {@code method} is {@code GET}, {@code POST} or {@code *} (any method, row 27). A path ending in
     * {@code /*} matches that prefix. {@code roles} is empty for a public route.
     */
    public record Route(int number, String path, String method, boolean authRequired, Set<Role> roles, Tier tier) {

        public Route {
            roles = Collections.unmodifiableSet(roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles));
        }

        public boolean isPost() {
            return "POST".equals(method);
        }

        /** {@code true} for a {@link Tier#TEMPORARY} route: the CSRF check is skipped because the route changes nothing. */
        public boolean csrfExempt() {
            return tier == Tier.TEMPORARY;
        }

        boolean matches(String requestPath, String requestMethod) {
            boolean methodOk = "*".equals(method) || method.equals(requestMethod);
            if (!methodOk) {
                return false;
            }
            return path.endsWith("/*") ? requestPath.startsWith(path.substring(0, path.length() - 1)) : path.equals(requestPath);
        }
    }

    /** What a filter needs to know about one request. */
    public record Access(boolean known, boolean authRequired, Set<Role> roles) {

        /** A role may use the route if the set contains it; a public route needs no role. */
        public boolean allows(Role role) {
            return !authRequired || roles.contains(role);
        }
    }

    private static final Set<Role> ANY = EnumSet.allOf(Role.class);
    private static final Set<Role> ADMIN = EnumSet.of(Role.ADMIN);
    private static final Set<Role> NONE = EnumSet.noneOf(Role.class);
    private static final Access UNKNOWN = new Access(false, true, Collections.unmodifiableSet(EnumSet.of(Role.ADMIN)));

    private static final RoutePolicy STANDARD = new RoutePolicy(table());

    private final List<Route> routes;

    private RoutePolicy(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    /** The production table. */
    public static RoutePolicy standard() {
        return STANDARD;
    }

    /** The policy the filters of this web application must use. */
    public static RoutePolicy from(ServletContext context) {
        return context.getAttribute(ATTRIBUTE) instanceof RoutePolicy policy ? policy : STANDARD;
    }

    /** A copy of this policy with extra routes appended (tests only). */
    public RoutePolicy withRoutes(List<Route> extra) {
        List<Route> all = new ArrayList<>(routes);
        all.addAll(extra);
        return new RoutePolicy(all);
    }

    private static List<Route> table() {
        List<Route> t = new ArrayList<>();
        t.add(new Route(1, "/", "GET", false, NONE, Tier.MUST));
        t.add(new Route(2, "/login", "GET", false, NONE, Tier.MUST));
        t.add(new Route(3, "/login", "POST", false, NONE, Tier.MUST));
        t.add(new Route(4, "/logout", "POST", true, ANY, Tier.MUST));
        t.add(new Route(5, "/dashboard", "GET", true, ANY, Tier.MUST));
        t.add(new Route(6, "/orders", "GET", true, ANY, Tier.MUST));
        t.add(new Route(7, "/orders/view", "GET", true, ANY, Tier.MUST));
        t.add(new Route(8, "/orders/new", "GET", true, ANY, Tier.SHOULD));
        t.add(new Route(9, "/orders/new", "POST", true, ANY, Tier.SHOULD));
        t.add(new Route(10, "/orders/cancel", "POST", true, ANY, Tier.MUST));
        t.add(new Route(11, "/inventory", "GET", true, ANY, Tier.MUST));
        t.add(new Route(12, "/admin/menu", "GET", true, ADMIN, Tier.SHOULD));
        t.add(new Route(13, "/admin/menu", "POST", true, ADMIN, Tier.SHOULD));
        t.add(new Route(14, "/admin/stock", "GET", true, ADMIN, Tier.SHOULD));
        t.add(new Route(15, "/admin/stock", "POST", true, ADMIN, Tier.SHOULD));
        t.add(new Route(16, "/admin/simulator", "GET", true, ADMIN, Tier.MUST));
        t.add(new Route(17, "/admin/simulator", "POST", true, ADMIN, Tier.MUST));
        t.add(new Route(18, "/admin/workers", "GET", true, ADMIN, Tier.SHOULD));
        t.add(new Route(19, "/admin/workers", "POST", true, ADMIN, Tier.SHOULD));
        t.add(new Route(20, "/admin/snapshot", "POST", true, ADMIN, Tier.SHOULD));
        t.add(new Route(21, "/admin/reports", "GET", true, ADMIN, Tier.SHOULD));
        t.add(new Route(22, "/admin/reports/daily.csv", "GET", true, ADMIN, Tier.SHOULD));
        t.add(new Route(23, "/admin/users", "GET", true, ADMIN, Tier.SHOULD));
        t.add(new Route(24, "/admin/users", "POST", true, ADMIN, Tier.SHOULD));
        t.add(new Route(26, "/static/*", "GET", false, NONE, Tier.MUST));
        t.add(new Route(27, "/error", "*", false, NONE, Tier.MUST));
        t.add(new Route(28, "/admin/compensations", "GET", true, ADMIN, Tier.MUST));
        t.add(new Route(29, "/admin/compensations", "POST", true, ADMIN, Tier.MUST));
        // infrastructure probe of the launcher and the smoke tests; not a row of SDD 10.2
        t.add(new Route(0, "/health", "GET", false, NONE, Tier.MUST));
        return t;
    }

    /** Every route of the table, for the access-matrix and CSRF tests. */
    public List<Route> allRoutes() {
        return routes;
    }

    /** The row for this request, if any. */
    public Optional<Route> find(String path, String method) {
        String m = normalise(method);
        for (Route route : routes) {
            if (route.matches(path, m)) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    /** Access rules for this request; an unmapped path or method is protected and ADMIN-only. */
    public Access accessFor(String path, String method) {
        return find(path, method).map(r -> new Access(true, r.authRequired(), r.roles())).orElse(UNKNOWN);
    }

    /** Roles allowed on the route: empty for a public route, ADMIN only when unmapped. */
    public Set<Role> requiredRoles(String path, String method) {
        Access access = accessFor(path, method);
        return access.authRequired() ? access.roles() : NONE;
    }

    /** {@code true} for a mapped route that needs no login. */
    public boolean isPublic(String path, String method) {
        Access access = accessFor(path, method);
        return access.known() && !access.authRequired();
    }

    /**
     * {@code true} for the routes that must never open a session on their own ({@code /health} and {@code /static/*}, D-074).
     */
    public boolean createsNoSession(String path, String method) {
        String m = normalise(method);
        boolean safe = "GET".equals(m) || "OPTIONS".equals(m);
        return safe && ("/health".equals(path) || path.startsWith("/static/"));
    }

    private static String normalise(String method) {
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        return "HEAD".equals(m) ? "GET" : m;
    }
}
