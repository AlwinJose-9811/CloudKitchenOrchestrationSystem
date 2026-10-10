package com.cloudkitchen.web.listener;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.exception.CloudKitchenException;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the application context when the web application starts. The launcher hands over the configuration and, for
 * the embedded database, the JDBC URL as servlet-context attributes; on a standalone Tomcat the URL comes from
 * {@code db.url}. If a database is configured but cannot be initialised the context fails to start, so a broken
 * database is a visible start-up error and never a silently half-working application. Without any database
 * configuration (the Phase 1 probes) nothing is built.
 */
public class AppBootstrapListener implements ServletContextListener {

    public static final String CONFIG_ATTRIBUTE = "ck.config";
    public static final String JDBC_URL_ATTRIBUTE = "ck.jdbc.url";
    public static final String LAUNCHER_MARK_ATTRIBUTE = "ck.launcher.mark";
    public static final String LISTENER_SEEN_ATTRIBUTE = "ck.listener.seen";
    public static final String APP_CONTEXT_ATTRIBUTE = "ck.app";
    public static final String ORDER_QUERIES_ATTRIBUTE = "ck.orderQueryService";

    private static final Logger LOG = LoggerFactory.getLogger(AppBootstrapListener.class);

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        Object config = context.getAttribute(CONFIG_ATTRIBUTE);
        Object mark = context.getAttribute(LAUNCHER_MARK_ATTRIBUTE);
        boolean sameClass = config instanceof AppConfig;
        context.setAttribute(LISTENER_SEEN_ATTRIBUTE, "mark=" + mark + ", config-typed=" + sameClass);
        LOG.info("Context initialised (launcher attribute present: {}, config attribute usable: {})", mark != null, sameClass);
        if (!sameClass) {
            return;
        }
        AppConfig appConfig = (AppConfig) config;
        String jdbcUrl = context.getAttribute(JDBC_URL_ATTRIBUTE) instanceof String url ? url : appConfig.dbUrl().orElse(null);
        if (jdbcUrl == null) {
            LOG.info("No database is configured for this context; persistence services are not started");
            return;
        }
        try {
            AppContext app = AppContext.create(appConfig, jdbcUrl, Clock.systemUTC());
            context.setAttribute(APP_CONTEXT_ATTRIBUTE, app);
            context.setAttribute(ORDER_QUERIES_ATTRIBUTE, app.orderQueries());
        } catch (CloudKitchenException e) {
            LOG.error("Database initialisation failed ({}): {}", e.getCode(), e.getMessage());
            throw new IllegalStateException("Database initialisation failed (" + e.getCode() + "); the application was not started", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        if (context.getAttribute(APP_CONTEXT_ATTRIBUTE) instanceof AppContext app) {
            app.close();
            context.removeAttribute(APP_CONTEXT_ATTRIBUTE);
            context.removeAttribute(ORDER_QUERIES_ATTRIBUTE);
        }
        LOG.info("Context destroyed");
    }
}
