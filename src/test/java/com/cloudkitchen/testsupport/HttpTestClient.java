package com.cloudkitchen.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Thin wrapper over {@link HttpClient} for tests against a server on 127.0.0.1: no redirects, no cookie jar. */
public final class HttpTestClient {

    /** Status, headers and body of one response. */
    public record Reply(int status, HttpHeaders headers, String body) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;

    public HttpTestClient(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    public Reply get(String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                .timeout(Duration.ofSeconds(20)).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("GET " + pathAndQuery + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
