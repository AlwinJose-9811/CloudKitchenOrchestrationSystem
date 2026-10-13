package com.cloudkitchen.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin wrapper over {@link HttpClient} for tests against a server on 127.0.0.1: redirects are never followed (so PRG can
 * be asserted). {@code new HttpTestClient(port)} keeps no cookies, as in Phase 1; {@link #withCookies} keeps a simple
 * cookie jar by hand (not {@code CookieManager}, which would hide attributes such as {@code Secure} and hide fixation).
 */
public final class HttpTestClient {

    /** Status, headers and body of one response. */
    public record Reply(int status, HttpHeaders headers, String body) {

        /** The {@code Location} header, or {@code null}. */
        public String location() {
            return headers.firstValue("Location").orElse(null);
        }
    }

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]*)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final boolean keepCookies;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public HttpTestClient(int port) {
        this(port, false);
    }

    private HttpTestClient(int port, boolean keepCookies) {
        this.base = "http://127.0.0.1:" + port;
        this.keepCookies = keepCookies;
    }

    /** A client with its own cookie jar (one browser). */
    public static HttpTestClient withCookies(int port) {
        return new HttpTestClient(port, true);
    }

    public Reply get(String pathAndQuery) {
        return send("GET", pathAndQuery, Map.of(), null);
    }

    public Reply get(String pathAndQuery, Map<String, String> headers) {
        return send("GET", pathAndQuery, headers, null);
    }

    /** POST of an already encoded form body. */
    public Reply post(String path, String formBody) {
        return send("POST", path, Map.of("Content-Type", "application/x-www-form-urlencoded"), formBody);
    }

    /** POST of form fields, percent-encoded as UTF-8. */
    public Reply postForm(String path, Map<String, String> fields) {
        return post(path, encode(fields));
    }

    public static String encode(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    public Reply send(String method, String path, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        headers.forEach(builder::header);
        if (keepCookies && !cookies.isEmpty() && !headers.containsKey("Cookie")) {
            StringBuilder jar = new StringBuilder();
            cookies.forEach((name, value) -> jar.append(jar.length() == 0 ? "" : "; ").append(name).append('=').append(value));
            builder.header("Cookie", jar.toString());
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (keepCookies) {
                remember(response.headers());
            }
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private void remember(HttpHeaders headers) {
        for (String header : headers.allValues("Set-Cookie")) {
            String[] parts = header.split(";");
            int eq = parts[0].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = parts[0].substring(0, eq).trim();
            String value = parts[0].substring(eq + 1).trim();
            boolean expired = header.toLowerCase(java.util.Locale.ROOT).contains("max-age=0") || value.isEmpty();
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    /** The jar's value for a cookie, or {@code null}. */
    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void clearCookies() {
        cookies.clear();
    }

    /** The value of the first hidden {@code _csrf} input of a page, or {@code null}. */
    public static String extractCsrf(String html) {
        Matcher m = CSRF.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** Asserts a 302 whose {@code Location} ends with {@code path} (the redirect is absolute or relative to the context). */
    public static void assertRedirect(Reply reply, String path) {
        assertEquals(302, reply.status(), "expected a redirect to " + path + " but got " + reply.status());
        String location = reply.location();
        assertTrue(location != null && (location.equals(path) || location.endsWith(path)), "Location was " + location + ", expected " + path);
    }
}
