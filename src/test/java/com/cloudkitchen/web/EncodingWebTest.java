package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.DbFixture;
import com.cloudkitchen.testsupport.HttpTestClient;
import com.cloudkitchen.testsupport.HttpTestClient.Reply;
import com.cloudkitchen.testsupport.TestApp;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-WEB-04: UTF-8 on the request and on the response, through the real filter chain of the production descriptor. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncodingWebTest {

    private static final String HINDI_AND_EMOJI = "आशा राव 🍛 éü";

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

    @Test
    void aFormPostWithoutACharsetIsDecodedAsUtf8AndRoundTripsHindiAndEmoji() {
        // the form content type below carries no charset; the filter must supply UTF-8 before the parameters are read
        Reply reply = http.postForm("/__test/echo", Map.of("name", HINDI_AND_EMOJI));
        assertEquals(200, reply.status());
        assertEquals("name=" + HINDI_AND_EMOJI, reply.body());
    }

    @Test
    void aDeclaredCharsetOnTheRequestIsKept() {
        // ISO-8859-1 e-acute is the single byte %E9; with UTF-8 forced it would be a replacement character
        Reply reply = http.send("POST", "/__test/echo", Map.of("Content-Type", "application/x-www-form-urlencoded; charset=ISO-8859-1"), "name=%E9");
        assertEquals("name=é", reply.body());
    }

    @Test
    void htmlResponsesDeclareUtf8InTheHeaderAndInTheDocument() {
        Reply notFound = http.get("/static/missing.css");
        assertEquals(404, notFound.status());
        assertTrue(notFound.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("charset=utf-8"), notFound.headers().toString());
        assertTrue(notFound.body().contains("<meta charset=\"UTF-8\">"));
    }

    @Test
    void plainTextResponsesDeclareUtf8() {
        Reply session = http.get("/__test/session");
        assertTrue(session.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("charset=utf-8"), session.headers().toString());
        Reply echo = http.postForm("/__test/echo", Map.of("name", "x"));
        assertTrue(echo.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("charset=utf-8"), echo.headers().toString());
    }

    @Test
    void aQueryStringIsDecodedAsUtf8() {
        Reply reply = http.get("/__test/flash-add?text=" + java.net.URLEncoder.encode(HINDI_AND_EMOJI, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(302, reply.status());
        // the stored flash is rendered back by the layout
        HttpTestClient browser = app.browser();
        browser.get("/__test/flash-add?text=" + java.net.URLEncoder.encode(HINDI_AND_EMOJI, java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(browser.get("/__test/render").body().contains(HINDI_AND_EMOJI));
    }
}
