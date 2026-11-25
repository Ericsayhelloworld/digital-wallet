package com.example.wallet.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in for bank-simulator in the tests (Section 6.6), plus the JWKS document of the test tokens.
 * Its behaviour is switched per test: down, hang, decline, settle, webhookCopies.
 */
final class FakeBank {

    static final String API_KEY = "test-api-key";
    static final String SECRET = "test-webhook-secret";

    volatile boolean down;
    volatile boolean hang;
    volatile boolean decline;
    /** false: transactions stay PENDING at the bank (no webhook, and GET answers PENDING) */
    volatile boolean settle = true;
    volatile int webhookCopies = 1;
    volatile String webhookUrl;

    private final HttpServer server;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final Map<String, Map<String, Object>> transactions = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    FakeBank(String jwks) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/jwks", exchange -> send(exchange, 200, jwks));
        server.createContext("/bank/v1/transactions", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void reset() {
        down = false;
        hang = false;
        decline = false;
        settle = true;
        webhookCopies = 1;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (down) {
                send(exchange, 503, "{\"code\":\"BANK_UNAVAILABLE\"}");
                return;
            }
            if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
                send(exchange, 401, "{\"code\":\"UNAUTHENTICATED\"}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                create(exchange);
            } else {
                String reference = exchange.getRequestURI().getPath().substring("/bank/v1/transactions/".length());
                Map<String, Object> transaction = transactions.get(reference);
                if (transaction == null) {
                    send(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
                } else {
                    send(exchange, 200, Api.JSON.writeValueAsString(transaction));
                }
            }
        } catch (Exception e) {
            send(exchange, 500, "{}");
        }
    }

    @SuppressWarnings("unchecked")
    private void create(HttpExchange exchange) throws Exception {
        Map<String, Object> request = Api.JSON.readValue(exchange.getRequestBody(), Map.class);
        String reference = (String) request.get("reference");
        Map<String, Object> transaction;
        boolean isNew;
        synchronized (this) { // BNK-2: idempotent by reference
            transaction = transactions.get(reference);
            isNew = transaction == null;
            if (isNew) {
                String status = !settle ? "PENDING" : decline ? "FAILED" : "SUCCEEDED";
                transaction = new LinkedHashMap<>();
                transaction.put("bankTransactionId", "bk-test-" + counter.incrementAndGet());
                transaction.put("reference", reference);
                transaction.put("type", request.get("type"));
                transaction.put("amount", request.get("amount"));
                transaction.put("status", status);
                transaction.put("reason", "FAILED".equals(status) ? "DECLINED" : null);
                transactions.put(reference, transaction);
            }
        }
        if (isNew && !"PENDING".equals(transaction.get("status"))) {
            sendWebhooks(Api.JSON.writeValueAsString(transaction));
        }
        if (isNew && hang) {
            Thread.sleep(2500); // longer than the client timeout of the tests
        }
        Map<String, Object> answer = new LinkedHashMap<>(transaction);
        if (isNew) {
            answer.put("status", "PENDING");
            answer.put("reason", null);
        }
        send(exchange, isNew ? 202 : 200, Api.JSON.writeValueAsString(answer));
    }

    /** The copies are sent at the same moment, from separate threads. */
    private void sendWebhooks(String body) {
        int copies = webhookCopies;
        for (int i = 0; i < copies; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                    String timestamp = Long.toString(Instant.now().getEpochSecond());
                    http.send(HttpRequest.newBuilder(URI.create(webhookUrl))
                                    .header("Content-Type", "application/json")
                                    .header("X-Bank-Timestamp", timestamp)
                                    .header("X-Bank-Signature", sign(SECRET, timestamp + "." + body))
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                    // like the real bank: no retry (BNK-4)
                }
            });
        }
    }

    static String sign(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
