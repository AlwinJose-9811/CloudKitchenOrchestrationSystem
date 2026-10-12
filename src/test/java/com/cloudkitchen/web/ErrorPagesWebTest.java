package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import com.cloudkitchen.testsupport.TestServlets;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

/**
 * FR-WEB-05, D-077: 400, 403, 404, 405, 500, any other status and an uncaught exception all end in a generic page that
 * shows an error id and nothing else, while the server log keeps the detail under that id.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorPagesWebTest {

    private static final Pattern REFERENCE = Pattern.compile("Reference: <code>([0-9a-f]{8})</code>");
    private static final List<String> FORBIDDEN = List.of("Exception", "at com.", "SQL", "java.", "SELECT", "staff_users", "Tomcat", "org.apache",
            "Caused by", "stack", "jakarta.", "cloudkitchen.secret", TestServlets.SECRET_DETAIL);

    private DbFixture db;
    private TestApp app;
    private HttpTestClient http;
    private HttpTestClient admin;
    private ListAppender<ILoggingEvent> log;
    private Logger errorLogger;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(Clock.systemUTC());
        app = TestApp.start(db);
        http = new HttpTestClient(app.port());
        admin = app.adminBrowser();
        errorLogger = (Logger) LoggerFactory.getLogger("com.cloudkitchen.web.controller.ErrorServlet");
        log = new ListAppender<>();
        log.start();
        errorLogger.addAppender(log);
    }

    @AfterAll
    void stop() {
        if (errorLogger != null && log != null) {
            errorLogger.detachAppender(log);
        }
        if (app != null) {
            app.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static void assertGeneric(String what, Reply reply, int status) {
        assertEquals(status, reply.status(), what);
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), what);
        for (String word : FORBIDDEN) {
            assertFalse(reply.body().contains(word), what + " leaks \"" + word + "\": " + reply.body());
        }
        assertTrue(REFERENCE.matcher(reply.body()).find(), what + " must show an error id: " + reply.body());
        assertTrue(reply.body().contains("<h1>" + status), what + " heading: " + reply.body());
    }

    @Test
    void badRequestIsAGenericPage() {
        assertGeneric("malformed integer", http.get("/__test/params?n=abc"), 400);
        assertGeneric("malformed long", http.get("/__test/params?id=1x"), 400);
        assertGeneric("unknown enum constant", http.get("/__test/params?kind=Z"), 400);
    }

    @Test
    void forbiddenNotFoundAndMethodNotAllowedAreGenericPages() {
        assertGeneric("403", http.get("/__test/status?code=403"), 403);
        assertGeneric("404 unknown route for a logged-in administrator", admin.get("/no-such-page"), 404);
        assertGeneric("404 from the default servlet for a missing static file", http.get("/static/missing.css"), 404);
        assertGeneric("404 from the container for a protected file", http.get("/WEB-INF/web.xml"), 404);
        String token = TestApp.tokenOf(admin);
        assertGeneric("405 after the CSRF check", admin.postForm("/health", Map.of("_csrf", token)), 405);
        assertGeneric("405 for PUT", admin.send("PUT", "/health?_csrf=" + token, Map.of(), "x"), 405);
        assertGeneric("403 from the CSRF check for a POST without a token", http.post("/health", ""), 403);
    }

    @Test
    void anUncaughtExceptionIsAGeneric500AndTheDetailIsOnlyInTheServerLog() {
        log.list.clear();
        Reply reply = http.get("/__test/boom");
        assertGeneric("uncaught exception", reply, 500);
        String id = idOf(reply);
        ILoggingEvent event = log.list.stream().filter(e -> e.getFormattedMessage().contains("errorId=" + id)).findFirst()
                .orElseThrow(() -> new AssertionError("no log event for the id " + id + ": " + log.list));
        assertEquals(Level.ERROR, event.getLevel());
        IThrowableProxy proxy = event.getThrowableProxy();
        assertNotNull(proxy, "the stack trace is logged");
        assertTrue(proxy.getMessage().contains(TestServlets.SECRET_DETAIL), "the exception detail is kept on the server");
        assertTrue(event.getFormattedMessage().contains("path=/__test/boom"));
    }

    @Test
    void anUncaughtExceptionOnAPostIsAlsoGeneric() {
        assertGeneric("POST to a failing servlet", http.post("/__test/boom", "a=b"), 500);
    }

    @Test
    void anyOtherStatusUsesTheGeneric500ViewButKeepsItsStatusCode() {
        for (int code : new int[] {408, 409, 429, 503}) {
            Reply reply = http.get("/__test/status?code=" + code);
            assertGeneric("status " + code, reply, code);
        }
    }

    @Test
    void theErrorPageCanBeRequestedDirectlyAndShowsA404() {
        assertGeneric("GET /error", http.get("/error"), 404);
        HttpTestClient visitor = app.browser();
        String token = HttpTestClient.extractCsrf(visitor.get("/login").body());
        assertGeneric("POST /error with a valid token", visitor.postForm("/error", Map.of("_csrf", token)), 404);
    }

    @Test
    void everyErrorGetsADifferentIdAndA4xxIsLoggedWithoutAStackTrace() {
        log.list.clear();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(idOf(http.get("/static/missing-" + i + ".css")));
        }
        assertEquals(20, ids.size(), "error ids are random per error");
        assertTrue(log.list.stream().allMatch(e -> e.getLevel() == Level.INFO && e.getThrowableProxy() == null), log.list.toString());
        assertEquals(20, log.list.size());
    }

    @Test
    void controlCharactersInTheRequestPathNeverReachTheLogLine() {
        log.list.clear();
        Reply reply = http.get("/static/no-such%0d%0aFAKE-LOG-LINE");
        assertEquals(404, reply.status());
        String message = log.list.get(0).getFormattedMessage();
        assertFalse(message.contains("\n") || message.contains("\r"), message);
    }

    @Test
    void thePageStillRendersTheLayoutWithoutOpeningASession() {
        Reply reply = http.get("/static/missing.css");
        assertTrue(reply.body().contains("<a class=\"brand\""), reply.body());
        assertTrue(reply.body().contains("All times are UTC."));
        assertTrue(reply.headers().allValues("Set-Cookie").isEmpty(), "an error page must not create a session");
        assertNotEquals("", idOf(reply));
    }

    private static String idOf(Reply reply) {
        Matcher m = REFERENCE.matcher(reply.body());
        assertTrue(m.find(), reply.body());
        return m.group(1);
    }
}
