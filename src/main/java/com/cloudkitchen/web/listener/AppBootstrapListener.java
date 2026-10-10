package com.cloudkitchen.web.listener;

import com.cloudkitchen.config.AppConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 1 version: only proves that an attribute set by {@code Launcher} before start is visible here (open
 * question O-3). Later phases build the application context in {@link #contextInitialized}.
 */
public class AppBootstrapListener implements ServletContextListener {

    public static final String CONFIG_ATTRIBUTE = "ck.config";
    public static final String LAUNCHER_MARK_ATTRIBUTE = "ck.launcher.mark";
    public static final String LISTENER_SEEN_ATTRIBUTE = "ck.listener.seen";

    private static final Logger LOG = LoggerFactory.getLogger(AppBootstrapListener.class);

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        Object config = context.getAttribute(CONFIG_ATTRIBUTE);
        Object mark = context.getAttribute(LAUNCHER_MARK_ATTRIBUTE);
        boolean sameClass = config instanceof AppConfig;
        context.setAttribute(LISTENER_SEEN_ATTRIBUTE, "mark=" + mark + ", config-typed=" + sameClass);
        LOG.info("Context initialised (launcher attribute present: {}, config attribute usable: {})", mark != null, sameClass);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        LOG.info("Context destroyed");
    }
}
