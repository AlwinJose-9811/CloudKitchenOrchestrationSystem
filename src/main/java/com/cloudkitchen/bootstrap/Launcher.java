package com.cloudkitchen.bootstrap;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.db.DataSourceFactory;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.listener.AppBootstrapListener;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the application with an embedded Tomcat 10.1 (and, optionally, the embedded database). Phase 1 version:
 * it serves {@code src/main/webapp} and hands the configuration to the listener through a servlet-context
 * attribute. The same {@code web.xml} serves the standalone WAR.
 */
public final class Launcher {

    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    private Launcher() {
    }

    public static void main(String[] args) throws CloudKitchenException {
        AppConfig config = AppConfig.load();
        RunningApp app = start(config, true, Path.of("data", "tomcat"));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "ck-shutdown-hook"));
        LOG.info("Cloud Kitchen is up: http://localhost:{}/demo/orders, /spike.jsp and /health (Ctrl+C stops it)", app.port());
        app.await();
    }

    /** Starts the optional database, then Tomcat. Everything already started is stopped if a later step fails. */
    public static RunningApp start(AppConfig config, boolean withDatabase, Path tomcatBaseDir)
            throws CloudKitchenException {
        DbBootstrap database = null;
        try {
            String jdbcUrl = null;
            if (withDatabase && "embedded".equals(config.dbMode())) {
                database = DbBootstrap.start(DbBootstrap.settingsFrom(config));
                jdbcUrl = DataSourceFactory.embeddedUrl(database.host(), database.port(), database.database());
            }
            return new RunningApp(startTomcat(config, tomcatBaseDir, jdbcUrl), database);
        } catch (CloudKitchenException | RuntimeException | Error e) {
            if (database != null) {
                database.close();
            }
            throw e;
        }
    }

    private static Tomcat startTomcat(AppConfig config, Path baseDir, String jdbcUrl) throws CloudKitchenException {
        Path docBase = Path.of("src", "main", "webapp").toAbsolutePath();
        Path classes = Path.of("target", "classes").toAbsolutePath();
        if (!Files.isDirectory(docBase)) {
            throw new CloudKitchenException("WEBAPP_NOT_FOUND", "Web application directory not found: " + docBase
                    + " (start the launcher from the project root)");
        }
        // Logback's servlet integration stops the logger context when a web application is destroyed. The embedded
        // launcher owns the JVM, and its shutdown hook must still be able to log after Tomcat has stopped the context.
        System.setProperty("logbackDisableServletContainerInitializer", "true");
        Tomcat tomcat = new Tomcat();
        try {
            tomcat.setBaseDir(Files.createDirectories(baseDir.toAbsolutePath()).toString());
            tomcat.getServer().setPort(-1); // no shutdown port
            Connector connector = tomcat.getConnector();
            connector.setPort(config.serverPort());
            ((AbstractProtocol<?>) connector.getProtocolHandler()).setAddress(InetAddress.getByName("127.0.0.1"));
            tomcat.getHost().setAutoDeploy(false);

            ErrorReportValve errors = new ErrorReportValve();
            errors.setShowReport(false);
            errors.setShowServerInfo(false);
            tomcat.getHost().getPipeline().addValve(errors);
            ((StandardHost) tomcat.getHost()).setErrorReportValveClass(ErrorReportValve.class.getName());

            Context context = tomcat.addWebapp("", docBase.toString());
            // Under "mvn exec:java" the application and Tomcat classes sit in an isolated loader, not the system one.
            context.setParentClassLoader(Launcher.class.getClassLoader());
            WebappLoader loader = new WebappLoader();
            loader.setDelegate(true); // one copy of every class: the listener must see the launcher's AppConfig type
            context.setLoader(loader);
            WebResourceRoot resources = new StandardRoot(context);
            resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", classes.toString(), "/"));
            context.setResources(resources);
            context.getServletContext().setAttribute(AppBootstrapListener.CONFIG_ATTRIBUTE, config);
            if (jdbcUrl != null) {
                context.getServletContext().setAttribute(AppBootstrapListener.JDBC_URL_ATTRIBUTE, jdbcUrl);
            }
            context.getServletContext().setAttribute(AppBootstrapListener.LAUNCHER_MARK_ATTRIBUTE,
                    "launcher-" + UUID.randomUUID());

            tomcat.start();
            if (!context.getState().isAvailable()) { // Tomcat only logs a failed context; the launcher must not report "up"
                stopQuietly(tomcat);
                throw new CloudKitchenException("WEBAPP_NOT_STARTED", "The web application did not start (state "
                        + context.getState() + "); see the Tomcat log above");
            }
            return tomcat;
        } catch (LifecycleException | IOException e) {
            stopQuietly(tomcat);
            throw new CloudKitchenException("TOMCAT_START_FAILED", "Embedded Tomcat failed to start: " + e.getMessage(), e);
        }
    }

    private static void stopQuietly(Tomcat tomcat) {
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException e) {
            LOG.warn("Tomcat did not stop cleanly after a failed start: {}", e.toString());
        }
    }

    /** A running launcher: Tomcat plus, optionally, the embedded database. */
    public static final class RunningApp implements AutoCloseable {

        private final Tomcat tomcat;
        private final DbBootstrap database;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private boolean closed;

        private RunningApp(Tomcat tomcat, DbBootstrap database) {
            this.tomcat = tomcat;
            this.database = database;
        }

        /** The port of the embedded database, or -1 when no embedded database was started (useful when {@code db.embedded.port=0}). */
        public int databasePort() {
            return database == null ? -1 : database.port();
        }

        /** The port Tomcat actually listens on (useful when {@code server.port=0}). */
        public int port() {
            return tomcat.getConnector().getLocalPort();
        }

        /**
         * Blocks until {@link #close()} has completely finished. Returning earlier (Tomcat's own await returns as
         * soon as a stop begins) would let main() end while the shutdown hook still runs, and "mvn exec:java" then
         * discards its class loader under the hook.
         */
        public void await() {
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Stops Tomcat, then the database. Idempotent; never throws. */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                try {
                    tomcat.stop();
                    tomcat.destroy();
                } catch (LifecycleException | RuntimeException e) {
                    LOG.error("Tomcat did not stop cleanly: {}", e.toString());
                }
                if (database != null) {
                    database.close();
                }
                LOG.info("Cloud Kitchen stopped");
            } finally {
                stopped.countDown();
            }
        }
    }

}
