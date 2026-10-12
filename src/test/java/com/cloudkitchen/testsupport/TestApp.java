package com.cloudkitchen.testsupport;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.bootstrap.Launcher;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.listener.AppBootstrapListener;
import com.cloudkitchen.web.support.AppServices;
import com.cloudkitchen.web.support.RoutePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.catalina.Context;

/**
 * The whole application for a web test: the real {@link Launcher} (embedded Tomcat on a random port, the production
 * {@code web.xml}) on the shared embedded test database, with the real {@link AppContext} built by the listener, plus
 * the test-only servlets of {@link TestServlets}. Accounts are seeded from random one-time credentials, never from a file.
 * {@link #close()} stops Tomcat, which runs the listener's {@code contextDestroyed} and so the ordered shutdown.
 */
public final class TestApp implements AutoCloseable {

    private final Launcher.RunningApp running;
    private final Context context;
    private final Path tomcatDir;
    private final Properties properties;
    private volatile boolean sessionFileWritten;

    private TestApp(Launcher.RunningApp running, Context context, Path tomcatDir, Properties properties) {
        this.running = running;
        this.context = context;
        this.tomcatDir = tomcatDir;
        this.properties = properties;
    }

    /** {@code overrides} are alternating configuration keys and values. */
    public static TestApp start(DbFixture db, String... overrides) throws CloudKitchenException {
        Properties p = AppFixture.properties(db, overrides);
        p.setProperty("db.mode", "external");
        p.setProperty("db.url", db.jdbcUrl());
        p.setProperty("server.port", "0");
        Path tomcatDir = Path.of("data", "test", "tomcat-" + TestDatabase.randomHex(4));
        Context[] holder = new Context[1];
        Launcher.RunningApp running = Launcher.start(AppConfig.fromProperties(p), false, tomcatDir, null, context -> {
            holder[0] = context;
            TestServlets.register(context);
            // the test servlets are public, read-only-by-convention routes next to the real table (never part of production)
            context.getServletContext().setAttribute(RoutePolicy.ATTRIBUTE, RoutePolicy.standard().withRoutes(List.of(
                    new RoutePolicy.Route(-100, "/__test/*", "*", false, Set.of(), RoutePolicy.Tier.TEMPORARY))));
        });
        TestApp app = new TestApp(running, holder[0], tomcatDir, p);
        try {
            // recovery runs on its own thread and the application accepts orders only when it has finished: a test that submits
            // straight after the start would otherwise be answered STARTING_UP (seen once in a full build, EV-059)
            if (app.appContext() != null && !app.appContext().awaitReady(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("The application did not finish starting within 60 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the application", e);
        }
        return app;
    }

    public int port() {
        return running.port();
    }

    /** A new browser (own cookie jar). */
    public HttpTestClient browser() {
        return HttpTestClient.withCookies(port());
    }

    /** The application context built by the listener; {@code null} only if the listener built none. */
    public AppContext appContext() {
        return (AppContext) context.getServletContext().getAttribute(AppBootstrapListener.APP_CONTEXT_ATTRIBUTE);
    }

    /** A browser that has logged in through the real login form; fails if the login is not accepted. */
    public HttpTestClient loginAs(String username, String password) {
        HttpTestClient browser = browser();
        String token = HttpTestClient.extractCsrf(browser.get("/login").body());
        HttpTestClient.Reply reply = browser.postForm("/login", java.util.Map.of("username", username, "password", password, "_csrf", token));
        HttpTestClient.assertRedirect(reply, "/dashboard");
        return browser;
    }

    public HttpTestClient adminBrowser() {
        return loginAs(adminUsername(), adminPassword());
    }

    public HttpTestClient staffBrowser() {
        return loginAs(staffUsername(), staffPassword());
    }

    /** The CSRF token of the session this browser holds, read from a page that renders the logout form (creates the session if needed). */
    public static String tokenOf(HttpTestClient browser) {
        return HttpTestClient.extractCsrf(browser.get("/__test/render?role=ADMIN").body());
    }

    /** The session manager's persistence file setting as Tomcat applied it ({@code null} or empty = persistence off). */
    public String sessionManagerPathname() {
        return ((org.apache.catalina.session.StandardManager) context.getManager()).getPathname();
    }

    /** {@code true} if stopping this application wrote a {@code SESSIONS.ser}; known only after {@link #close()}. */
    public boolean sessionFileWasWritten() {
        return sessionFileWritten;
    }

    public AppServices services() {
        return (AppServices) context.getServletContext().getAttribute(AppServices.ATTRIBUTE);
    }

    /**
     * Publishes a copy of the application services whose dashboard reads the "recovering" state from {@code recovering}
     * instead of from the recovery service, so that a test can show and hide the recovery banner at will, without timing.
     * Everything else is the real thing. {@link #restoreServices(AppServices)} puts the original back.
     */
    public AppServices useRecoveryFlag(java.util.concurrent.atomic.AtomicBoolean recovering) {
        AppServices original = services();
        AppContext app = appContext();
        com.cloudkitchen.service.DashboardService dashboard = new com.cloudkitchen.service.DashboardService(app.orderQueries(), app.stock(),
                app.orders(), app.orchestrator()::stationViews, app.metrics()::view, app.simulator()::isRunning, app.simulator()::getRate,
                recovering::get);
        context.getServletContext().setAttribute(AppServices.ATTRIBUTE, new AppServices(original.config(), original.clock(), original.auth(),
                original.orders(), original.orderQueries(), dashboard, original.menu(), original.stock(), original.reports(),
                original.simulator(), original.compensation(), original.workers(), original.snapshots()));
        return original;
    }

    /**
     * Publishes {@code change.apply(current)} as the application services, so that a test can swap one service for a double that
     * fails in a controlled way. Returns the services that were published before; {@link #restoreServices} puts them back.
     */
    public AppServices replaceServices(java.util.function.UnaryOperator<AppServices> change) {
        AppServices original = services();
        context.getServletContext().setAttribute(AppServices.ATTRIBUTE, change.apply(original));
        return original;
    }

    public void restoreServices(AppServices original) {
        context.getServletContext().setAttribute(AppServices.ATTRIBUTE, original);
    }

    public String adminUsername() {
        return properties.getProperty("bootstrap.admin.username");
    }

    public String adminPassword() {
        return properties.getProperty("bootstrap.admin.password");
    }

    public String staffUsername() {
        return properties.getProperty("bootstrap.staff.username");
    }

    public String staffPassword() {
        return properties.getProperty("bootstrap.staff.password");
    }

    @Override
    public void close() {
        AppContext app = appContext();
        running.close();
        if (app != null && !app.shutdownCoordinator().isDone()) {
            throw new AssertionError("closing the web application did not complete the application shutdown");
        }
        try (Stream<Path> files = Files.walk(tomcatDir)) {
            sessionFileWritten = files.anyMatch(f -> f.getFileName().toString().equals("SESSIONS.ser"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        TestDatabase.deleteTree(tomcatDir);
    }
}
