package com.example.notification.acceptance;

import com.example.notification.acceptance.Api.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ACC-2, scenarios 16 and 17 of Section 11.2 and the notification part of scenario 7,
 * against real PostgreSQL and Kafka (Testcontainers). Events are written to wallet.events by the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.issuer=" + TestTokens.ISSUER,
        "management.tracing.enabled=false"
})
class NotificationAcceptanceTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final TestTokens TOKENS = new TestTokens();
    static final HttpServer JWKS = jwksServer(TOKENS.jwks());
    static final KafkaProducer<String, String> PRODUCER;

    static {
        POSTGRES.start();
        KAFKA.start();
        Properties admin = new Properties();
        admin.put("bootstrap.servers", KAFKA.getBootstrapServers());
        try (AdminClient client = AdminClient.create(admin)) {
            client.createTopics(List.of(new NewTopic("wallet.events", 3, (short) 1),
                    new NewTopic("wallet.events.dlt", 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Properties producer = new Properties();
        producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        PRODUCER = new KafkaProducer<>(producer);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.jwt.jwks-uri", () -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @LocalServerPort
    int port;

    Api api;

    @BeforeEach
    void setUp() {
        api = new Api("http://localhost:" + port);
    }

    // =====================================================================================================
    // 16  the same event is delivered twice
    // =====================================================================================================
    @Test
    @DisplayName("16: the same event delivered twice: one notification per recipient (NTF-2)")
    void duplicateEvent() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String aliceToken = TOKENS.mint(alice, "alice", "Alice Chen", List.of("USER"));
        String bobToken = TOKENS.mint(bob, "bob", "Bob Lee", List.of("USER"));
        String adminToken = TOKENS.mint(UUID.randomUUID(), "admin", "Administrator", List.of("USER", "ADMIN"));
        long duplicatesBefore = api.get("/api/v1/notifications/admin/stats", adminToken).body().get("duplicatesIgnored").asLong();

        String transferId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transferId);
        payload.put("amount", 1250);
        payload.put("currency", "USD");
        payload.put("note", "Lunch");
        payload.put("from", party(alice, "Alice Chen"));
        payload.put("to", party(bob, "Bob Lee"));
        String event = envelope("transfer.completed", UUID.randomUUID().toString(), transferId, payload);

        publish(transferId, event);
        publish(transferId, event); // the very same event, with the same eventId

        await().atMost(Duration.ofSeconds(30)).until(() ->
                api.get("/api/v1/notifications/admin/stats", adminToken).body().get("duplicatesIgnored").asLong()
                        > duplicatesBefore);

        List<JsonNode> aliceNotes = notifications(aliceToken);
        List<JsonNode> bobNotes = notifications(bobToken);
        assertThat(aliceNotes).hasSize(1);
        assertThat(bobNotes).hasSize(1);
        assertThat(aliceNotes.get(0).get("text").asText()).isEqualTo("You sent $12.50 to Bob Lee");
        assertThat(bobNotes.get(0).get("text").asText()).isEqualTo("You received $12.50 from Alice Chen");
    }

    // =====================================================================================================
    // 17  a message that cannot be parsed, followed by valid events
    // =====================================================================================================
    @Test
    @DisplayName("17: an unparseable message goes to wallet.events.dlt; the valid events are processed (NTF-4)")
    void unparseableMessage() throws Exception {
        UUID carol = UUID.randomUUID();
        String carolToken = TOKENS.mint(carol, "carol", "Carol", List.of("USER"));
        String badKey = "bad-" + UUID.randomUUID();
        String badValue = "this is not json " + badKey;

        publish(badKey, badValue);
        String depositId = UUID.randomUUID().toString();
        publish(depositId, envelope("deposit.succeeded", UUID.randomUUID().toString(), depositId,
                ownerPayload("depositId", depositId, carol, 125_000)));

        await().atMost(Duration.ofSeconds(30)).until(() -> notifications(carolToken).size() == 1);
        assertThat(notifications(carolToken).get(0).get("text").asText())
                .isEqualTo("Your deposit of $1,250.00 is complete");

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "acceptance-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        AtomicBoolean found = new AtomicBoolean();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("wallet.events.dlt"));
            await().atMost(Duration.ofSeconds(30)).pollInSameThread().until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (badKey.equals(record.key()) && badValue.equals(record.value())) {
                        found.set(true); // with its key and its value
                    }
                }
                return found.get();
            });
        }
    }

    // =====================================================================================================
    // 7 (notification part)  user A requests a notification of user B
    // =====================================================================================================
    @Test
    @DisplayName("7: a notification of another user: 404 NOT_FOUND, and it stays unread (API-4)")
    void notificationOfAnotherUser() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String aToken = TOKENS.mint(a, "usera", "User A", List.of("USER"));
        String bToken = TOKENS.mint(b, "userb", "User B", List.of("USER"));
        String depositId = UUID.randomUUID().toString();
        publish(depositId, envelope("deposit.succeeded", UUID.randomUUID().toString(), depositId,
                ownerPayload("depositId", depositId, b, 500)));

        await().atMost(Duration.ofSeconds(30)).until(() -> notifications(bToken).size() == 1);
        String notificationId = notifications(bToken).get(0).get("id").asText();

        Response asA = api.post("/api/v1/notifications/" + notificationId + "/read", aToken, null, null);
        assertThat(asA.status()).isEqualTo(404);
        assertThat(asA.code()).isEqualTo("NOT_FOUND");
        assertThat(api.get("/api/v1/notifications/unread-count", bToken).body().get("count").asLong()).isEqualTo(1);
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    List<JsonNode> notifications(String token) {
        List<JsonNode> list = new ArrayList<>();
        api.get("/api/v1/notifications?limit=100", token).body().get("items").forEach(list::add);
        return list;
    }

    static void publish(String key, String value) throws Exception {
        PRODUCER.send(new ProducerRecord<>("wallet.events", key, value)).get(10, TimeUnit.SECONDS);
    }

    /** Section 7: the envelope */
    static String envelope(String type, String eventId, String aggregateId, Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("payload", payload);
        return Api.JSON.writeValueAsString(envelope);
    }

    static Map<String, Object> party(UUID userId, String displayName) {
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("walletId", UUID.randomUUID().toString());
        party.put("userId", userId.toString());
        party.put("displayName", displayName);
        return party;
    }

    static Map<String, Object> ownerPayload(String idField, String id, UUID userId, long amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(idField, id);
        payload.put("walletId", UUID.randomUUID().toString());
        payload.put("userId", userId.toString());
        payload.put("amount", amount);
        payload.put("currency", "USD");
        payload.put("failureReason", null);
        return payload;
    }

    static HttpServer jwksServer(String jwks) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] bytes = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
