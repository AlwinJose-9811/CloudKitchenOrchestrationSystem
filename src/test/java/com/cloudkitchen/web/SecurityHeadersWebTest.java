package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-WEB-12: the security headers are on every kind of response, and no page needs what the policy forbids. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHeadersWebTest {

    private static final String CSP = "default-src 'self'; style-src 'self'; script-src 'none'; frame-ancestors 'none'; form-action 'self'";

    private DbFixture db;
    private TestApp app;
    private HttpTestClient http;

    @BeforeAll
    void start() throws Exception {
        db = DbFixture.create(Clock.systemUTC());
        app = TestApp.start(db);
        http = new HttpTestClient(app.port());
    }

    @AfterAll
    void stop() {
        if (app != null) {
            app.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static void assertSecurityHeaders(String what, Reply reply) {
        assertEquals(List.of("nosniff"), reply.headers().allValues("X-Content-Type-Options"), what);
        assertEquals(List.of("DENY"), reply.headers().allValues("X-Frame-Options"), what);
        assertEquals(List.of("same-origin"), reply.headers().allValues("Referrer-Policy"), what);
        assertEquals(List.of(CSP), reply.headers().allValues("Content-Security-Policy"), what);
    }

    @Test
    void everyKindOfResponseCarriesTheFourHeaders() {
        assertSecurityHeaders("plain text /health", http.get("/health"));
        assertSecurityHeaders("rendered view", http.get("/__test/render"));
        assertSecurityHeaders("redirect", http.get("/__test/flash-add"));
        assertSecurityHeaders("redirect of an unmapped path to the login page", http.get("/no-such-page"));
        assertSecurityHeaders("404 page", http.get("/static/missing.css"));
        assertSecurityHeaders("400 page", http.get("/__test/params?n=abc"));
        assertSecurityHeaders("403 page from the CSRF check", http.post("/health", ""));
        assertSecurityHeaders("500 page after an uncaught exception", http.get("/__test/boom"));
        assertSecurityHeaders("static file", http.get("/static/app.css"));
        assertSecurityHeaders("default servlet 404 for a missing static file", http.get("/static/missing.css"));
    }

    @Test
    void theContentSecurityPolicyAllowsNoScriptAtAll() {
        String csp = http.get("/health").headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("script-src 'none'"), csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
        assertTrue(csp.contains("form-action 'self'"), csp);
        assertFalse(csp.contains("unsafe-inline") || csp.contains("unsafe-eval"), csp);
    }

    @Test
    void dynamicPagesAreNotCachedButStaticFilesAre() {
        for (String path : List.of("/health", "/__test/render", "/no-such-page", "/__test/boom")) {
            assertEquals(List.of("no-store"), http.get(path).headers().allValues("Cache-Control"), path);
        }
        Reply css = http.get("/static/app.css");
        assertEquals(200, css.status());
        assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"), css.headers().toString());
        assertTrue(css.headers().allValues("Cache-Control").isEmpty(), "static files stay cacheable: " + css.headers());
    }

    @Test
    void theHeadersAreSetExactlyOncePerResponse() {
        Reply reply = http.get("/__test/render");
        for (String name : List.of("X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Content-Security-Policy", "Cache-Control")) {
            assertEquals(1, reply.headers().allValues(name).size(), name + " must not be duplicated by the forward or error dispatch");
        }
    }

    @Test
    void renderedPagesNeedNoInlineScriptInlineStyleOrEventHandler() {
        for (String body : List.of(http.get("/__test/render").body(), http.get("/static/missing.css").body(),
                http.get("/__test/render?role=ADMIN").body())) {
            assertFalse(body.toLowerCase().contains("<script"), body);
            assertFalse(body.matches("(?s).*\\sstyle\\s*=.*"), body);
            assertFalse(body.matches("(?s).*\\son[a-z]+\\s*=.*"), body);
            assertTrue(body.contains("href=\"/static/app.css\""), "the only style comes from this origin");
        }
    }

    @Test
    void theErrorPagesDoNotRevealTheServer() {
        Reply notFound = http.get("/no-such-page");
        assertTrue(notFound.headers().firstValue("Server").isEmpty() || !notFound.headers().firstValue("Server").orElse("").toLowerCase().contains("tomcat"),
                "Server header: " + notFound.headers().firstValue("Server"));
    }

    @Test
    void headersAlsoCoverTheMethodsThatAreRefused() {
        Reply put = http.send("PUT", "/health", Map.of(), "x");
        assertEquals(403, put.status(), "no token: the CSRF filter refuses every non-safe method before routing");
        assertSecurityHeaders("PUT /health", put);
    }
}
