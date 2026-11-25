package com.example.wallet.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** A small HTTP client for the tests: returns the status, the JSON body and the headers, never throws on 4xx. */
final class Api {

    record Response(int status, JsonNode body, HttpHeaders headers) {

        String code() {
            return body != null && body.hasNonNull("code") ? body.get("code").asText() : null;
        }

        String text(String field) {
            return body.get(field).asText();
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;

    Api(String base) {
        this.base = base;
    }

    Response get(String path, String token) {
        return send("GET", path, token, Map.of(), null);
    }

    Response post(String path, String token, String idempotencyKey, Object body) {
        Map<String, String> headers = new HashMap<>();
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return send("POST", path, token, headers, body);
    }

    /** A String body is sent exactly as given (needed for signed webhooks). */
    Response send(String method, String path, String token, Map<String, String> headers, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json");
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            headers.forEach(request::header);
            String payload = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
            if (payload != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, payload == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(payload));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            if (!response.body().isEmpty()) {
                try {
                    json = JSON.readTree(response.body());
                } catch (Exception ignored) {
                    // not JSON
                }
            }
            return new Response(response.statusCode(), json, response.headers());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        }
    }
}
