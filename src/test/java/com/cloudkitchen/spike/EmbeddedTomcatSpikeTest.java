package com.cloudkitchen.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.bootstrap.Launcher;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.TestDatabase;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Phase 1 spike (S-13, FR-ENV-01): the real {@link Launcher} serves a servlet and a JSP that uses JSTL, on a random
 * port, with filter order, cookie attributes, context-attribute hand-over and hidden error details checked over HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedTomcatSpikeTest {

    private Launcher.RunningApp app;
    private HttpTestClient http;
    private Path tomcatDir;
    private int port;

    @BeforeAll
    void startServer() {
        Properties properties = new Properties();
        properties.setProperty("db.password", TestDatabase.randomHex(16));
        properties.setProperty("server.port", "0");
        tomcatDir = Path.of("data", "test", "tomcat-" + TestDatabase.randomHex(4));
        app = Launcher.start(AppConfig.fromProperties(properties), false, tomcatDir);
        port = app.port();
        http = new HttpTestClient(port);
    }

    @AfterAll
    void stopServer() {
        if (app != null) {
            app.close();
        }
        TestDatabase.deleteTree(tomcatDir);
        if (port > 0) {
            assertThrows(java.io.IOException.class, () -> new Socket("127.0.0.1", port).close(),
                    "the port must be closed after the launcher stops");
        }
    }

    @Test
    void healthReturns200WithExactBodyOk() {
        HttpTestClient.Reply reply = http.get("/health");
        assertEquals(200, reply.status());
        assertEquals("OK", reply.body());
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
    }

    @Test
    void healthNeedsNoSessionOrAuthentication() {
        HttpTestClient.Reply reply = http.get("/health");
        assertTrue(reply.headers().allValues("Set-Cookie").isEmpty(), "/health must not create a session");
    }

    @Test
    void jspRendersAndJstlCOutEscapesUserInput() {
        HttpTestClient.Reply reply = http.get("/spike.jsp?name=%3Cb%3Ex%3C%2Fb%3E");
        assertEquals(200, reply.status());
        String body = reply.body();
        assertTrue(body.contains("c:out says: &lt;b&gt;x&lt;/b&gt;"), "c:out must HTML-escape the parameter: " + body);
        assertFalse(body.contains("<b>x</b>"), "raw markup must never be echoed");
        assertTrue(body.contains("EL sum: 3"), "EL must be evaluated");
        assertFalse(body.contains("<c:out"), "the tag must be processed, not copied to the output");
        assertFalse(body.contains("taglib"), "the directive must not leak into the output");
    }

    @Test
    void jspUsesTheDefaultWhenNoParameterIsGiven() {
        assertTrue(http.get("/spike.jsp").body().contains("c:out says: no name given"));
    }

    @Test
    void servletContextAttributeSetByLauncherIsVisibleInTheListenerAndTheJsp() {
        String body = http.get("/spike.jsp").body();
        assertTrue(body.contains("launcher mark: launcher-"), body);
        assertTrue(body.contains("listener saw: mark=launcher-"), body);
        assertTrue(body.contains("config-typed=true"), "the listener must get the launcher's AppConfig type: " + body);
    }

    @Test
    void filtersRunInTheOrderOfWebXml() {
        assertEquals(List.of("first,second"), http.get("/health").headers().allValues("X-Filter-Order"));
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSiteLax() {
        List<String> cookies = http.get("/spike.jsp").headers().allValues("Set-Cookie");
        String session = cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(session.contains("HttpOnly"), session);
        assertTrue(session.contains("SameSite=Lax"), session);
    }

    @Test
    void errorPagesRevealNoServerDetails() {
        HttpTestClient.Reply reply = http.get("/does-not-exist");
        assertEquals(404, reply.status());
        assertFalse(reply.body().contains("Apache Tomcat"), reply.body());
        assertFalse(reply.body().contains("org.apache"), reply.body());
        assertFalse(reply.body().contains("Exception"), reply.body());
    }

    @Test
    void protectedDescriptorAndClassesAreNotServed() {
        assertEquals(404, http.get("/WEB-INF/web.xml").status());
        assertEquals(404, http.get("/META-INF/MANIFEST.MF").status());
    }

    /**
     * "mvn exec:java" runs the main class in an isolated class loader whose parent knows no application or Tomcat
     * class. Surefire puts everything on the system class path, so this test rebuilds the exec:java situation.
     */
    @Test
    void launcherWorksWhenLoadedThroughAnIsolatedClassLoaderLikeExecJava() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        Path isolatedDir = Path.of("data", "test", "tomcat-isolated-" + TestDatabase.randomHex(4));
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        Object running = null;
        URLClassLoader isolated = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        try {
            thread.setContextClassLoader(isolated);
            Properties properties = new Properties();
            properties.setProperty("db.password", TestDatabase.randomHex(16));
            properties.setProperty("server.port", "0");
            // The Tomcat started in @BeforeAll already registered the JVM-wide URL handler factory; a second copy of
            // Tomcat in this JVM must not try again (a real exec:java run has only one Tomcat).
            isolated.loadClass("org.apache.catalina.webresources.TomcatURLStreamHandlerFactory")
                    .getMethod("disable").invoke(null);
            Class<?> configClass = isolated.loadClass("com.cloudkitchen.config.AppConfig");
            Object config = configClass.getMethod("fromProperties", Properties.class).invoke(null, properties);
            Class<?> launcherClass = isolated.loadClass("com.cloudkitchen.bootstrap.Launcher");
            running = launcherClass.getMethod("start", configClass, boolean.class, Path.class)
                    .invoke(null, config, false, isolatedDir);
            Method port = running.getClass().getMethod("port");
            HttpTestClient client = new HttpTestClient((Integer) port.invoke(running));
            assertEquals("OK", client.get("/health").body());
            assertEquals(200, client.get("/health").status());
            assertTrue(client.get("/spike.jsp?name=x").body().contains("c:out says: x"));
        } finally {
            if (running != null) {
                running.getClass().getMethod("close").invoke(running);
            }
            thread.setContextClassLoader(previous);
            isolated.close(); // only after the launcher stopped: Tomcat still loads classes while stopping
            TestDatabase.deleteTree(isolatedDir);
        }
    }

    /**
     * main() blocks in await(); under exec:java a premature return tears the class loader down while the shutdown
     * hook is still stopping things. await() must therefore return only after close() has completely finished.
     */
    @Test
    void awaitReturnsOnlyAfterCloseHasCompletelyFinished() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("db.password", TestDatabase.randomHex(16));
        properties.setProperty("server.port", "0");
        Path dir = Path.of("data", "test", "tomcat-await-" + TestDatabase.randomHex(4));
        Launcher.RunningApp other = Launcher.start(AppConfig.fromProperties(properties), false, dir);
        int otherPort = other.port();
        AtomicBoolean portClosedWhenAwaitReturned = new AtomicBoolean();
        try {
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                other.await();
                try {
                    new Socket("127.0.0.1", otherPort).close();
                } catch (java.io.IOException expected) {
                    portClosedWhenAwaitReturned.set(true);
                }
            });
            Thread.sleep(300); // let the waiter block; the assertion below does not depend on this delay
            assertFalse(waiter.isDone(), "await() must block while the server runs");
            other.close();
            waiter.get(20, TimeUnit.SECONDS);
            assertTrue(portClosedWhenAwaitReturned.get(), "when await() returns the server must already be stopped");
        } finally {
            other.close();
            TestDatabase.deleteTree(dir);
        }
    }
}
