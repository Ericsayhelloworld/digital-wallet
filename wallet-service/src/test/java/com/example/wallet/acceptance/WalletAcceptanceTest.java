package com.example.wallet.acceptance;

import com.example.wallet.acceptance.Api.Response;
import com.example.wallet.operation.OperationKind;
import com.example.wallet.operation.OperationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ACC-2, scenarios 1 to 15 of Section 11.2, against real PostgreSQL and Kafka (Testcontainers).
 * The bank and the JWKS document are provided by FakeBank; tokens are signed by TestTokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.issuer=" + TestTokens.ISSUER,
        "app.bank.api-key=" + FakeBank.API_KEY,
        "app.bank.webhook-secret=" + FakeBank.SECRET,
        "app.bank.timeout-ms=1000",
        "app.bank.circuit.open-seconds=2",
        "app.reconciliation.pending-after-seconds=2",
        "app.reconciliation.interval-ms=500",
        "app.kafka.relay-interval-ms=200",
        "management.tracing.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalletAcceptanceTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final TestTokens TOKENS = new TestTokens();
    static final FakeBank BANK = new FakeBank(TOKENS.jwks());

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.bank.base-url", BANK::baseUrl);
        registry.add("app.jwt.jwks-uri", () -> BANK.baseUrl() + "/jwks");
    }

    @LocalServerPort
    int port;

    @Autowired
    OperationService operationService;

    Api api;
    String adminToken;

    record User(String token, String walletId) {
    }

    @BeforeEach
    void setUp() {
        api = new Api("http://localhost:" + port);
        BANK.reset();
        BANK.webhookUrl = "http://localhost:" + port + "/internal/webhooks/bank";
        adminToken = TOKENS.mint(UUID.randomUUID(), "admin", "Administrator", List.of("USER", "ADMIN"));
    }

    // =====================================================================================================
    // 1  32 threads send 2,000 random transfers among 10 wallets, including opposite directions
    // =====================================================================================================
    @Test
    @Order(1)
    @DisplayName("1: 2,000 concurrent random transfers: no technical failure, INV-1 to INV-6 hold (CON-1, CON-3)")
    void concurrentRandomTransfers() throws Exception {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            User user = newUser();
            fund(user, 100_000);
            users.add(user);
        }
        Random random = new Random(42);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            int from;
            int to;
            if (i % 5 == 0) { // many transfers in opposite directions between wallets 0 and 1
                from = (i / 5) % 2;
                to = 1 - from;
            } else {
                from = random.nextInt(10);
                to = (from + 1 + random.nextInt(9)) % 10;
            }
            long amount = 1 + random.nextInt(500);
            User a = users.get(from);
            User b = users.get(to);
            futures.add(pool.submit(() -> transfer(a, b, amount)));
        }
        for (Future<Response> future : futures) {
            Response response = future.get(120, TimeUnit.SECONDS);
            // no technical failure: only success, or a business rejection
            assertThat(response.status()).as(response.toString()).isIn(201, 422);
            if (response.status() == 422) {
                assertThat(response.code()).isEqualTo("INSUFFICIENT_FUNDS");
            }
        }
        pool.shutdown();
        assertThat(users.stream().mapToLong(this::balance).sum()).isEqualTo(1_000_000);
        assertIntegrity();
    }

    // =====================================================================================================
    // 2  10,000 cents and 20 concurrent transfers of 1,000 cents
    // =====================================================================================================
    @Test
    @Order(2)
    @DisplayName("2: 20 concurrent transfers of 1,000 from 10,000: exactly 10 complete (CON-2)")
    void exactlyTenOfTwentyComplete() throws Exception {
        User a = newUser();
        User b = newUser();
        fund(a, 10_000);
        List<Response> responses = concurrently(20, () -> transfer(a, b, 1000));
        assertThat(count(responses, 201, null)).isEqualTo(10);
        assertThat(count(responses, 422, "INSUFFICIENT_FUNDS")).isEqualTo(10);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(10_000);
    }

    // =====================================================================================================
    // 3  the same transfer with the same key 10 times at the same moment
    // =====================================================================================================
    @Test
    @Order(3)
    @DisplayName("3: same key 10 times at once: one transfer; stored response or REQUEST_IN_PROGRESS (IDM-2, IDM-6)")
    void sameKeyAtTheSameMoment() throws Exception {
        User a = newUser();
        User b = newUser();
        fund(a, 10_000);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("toWalletId", b.walletId(), "amount", 1250, "note", "same key");
        List<Response> responses = concurrently(10, () -> api.post("/api/v1/transfers", a.token(), key, body));

        Set<String> ids = new HashSet<>();
        for (Response r : responses) {
            boolean stored = r.status() == 201;
            boolean inProgress = r.status() == 409 && "REQUEST_IN_PROGRESS".equals(r.code());
            assertThat(stored || inProgress).as(r.toString()).isTrue();
            if (stored) {
                ids.add(r.text("id"));
            }
        }
        assertThat(ids).hasSize(1);
        assertThat(entries(a, "TRANSFER_OUT")).hasSize(1);
        assertThat(balance(a)).isEqualTo(10_000 - 1250);
    }

    // =====================================================================================================
    // 4  a stored key again with a different amount
    // =====================================================================================================
    @Test
    @Order(4)
    @DisplayName("4: stored key with a different amount: 422 IDEMPOTENCY_KEY_REUSED, one transfer (IDM-5)")
    void keyReusedWithDifferentBody() {
        User a = newUser();
        User b = newUser();
        fund(a, 10_000);
        String key = UUID.randomUUID().toString();
        Response first = api.post("/api/v1/transfers", a.token(), key, Map.of("toWalletId", b.walletId(), "amount", 100));
        assertThat(first.status()).isEqualTo(201);
        Response second = api.post("/api/v1/transfers", a.token(), key, Map.of("toWalletId", b.walletId(), "amount", 200));
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(entries(a, "TRANSFER_OUT")).hasSize(1);
        assertThat(balance(a)).isEqualTo(10_000 - 100);
    }

    // =====================================================================================================
    // 5  the response of a completed transfer is lost and the client repeats the request
    // =====================================================================================================
    @Test
    @Order(5)
    @DisplayName("5: lost response, repeated request: stored response with Idempotent-Replayed (IDM-4)")
    void lostResponseIsReplayed() {
        User a = newUser();
        User b = newUser();
        fund(a, 10_000);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("toWalletId", b.walletId(), "amount", 300);
        Response lost = api.post("/api/v1/transfers", a.token(), key, body); // the client never sees this one
        Response repeated = api.post("/api/v1/transfers", a.token(), key, body);

        assertThat(repeated.status()).isEqualTo(201);
        assertThat(repeated.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(repeated.text("id")).isEqualTo(lost.text("id"));
        assertThat(lost.header("Idempotent-Replayed")).isNull();
        assertThat(entries(a, "TRANSFER_OUT")).hasSize(1);
    }

    // =====================================================================================================
    // 6  transfers that together exceed the daily limit, one after another and concurrently
    // =====================================================================================================
    @Test
    @Order(6)
    @DisplayName("6: the amount sent never exceeds the daily limit, sequential and concurrent (MNY-3)")
    void dailyLimit() throws Exception {
        User receiver = newUser();

        User sequential = newUser();
        fund(sequential, 600_000);
        assertThat(transfer(sequential, receiver, 200_000).status()).isEqualTo(201);
        assertThat(transfer(sequential, receiver, 200_000).status()).isEqualTo(201);
        assertThat(transfer(sequential, receiver, 100_000).status()).isEqualTo(201);
        Response over = transfer(sequential, receiver, 1);
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("DAILY_LIMIT_EXCEEDED");
        assertThat(wallet(sequential).get("sentToday").asLong()).isEqualTo(500_000);

        User parallel = newUser();
        fund(parallel, 600_000);
        List<Response> responses = concurrently(10, () -> transfer(parallel, receiver, 100_000));
        assertThat(count(responses, 201, null)).isEqualTo(5);
        assertThat(count(responses, 422, "DAILY_LIMIT_EXCEEDED")).isEqualTo(5);
        assertThat(wallet(parallel).get("sentToday").asLong()).isEqualTo(500_000);
    }

    // =====================================================================================================
    // 7  user A requests resources of other users (the notification part is in notification-service)
    // =====================================================================================================
    @Test
    @Order(7)
    @DisplayName("7: a transfer between two other users, a deposit and a withdrawal of B: 404 (API-4)")
    void otherUsersResourcesAreNotFound() {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        fund(b, 10_000);
        String transferId = transfer(b, c, 100).text("id");
        String depositId = deposit(b, 500).text("id");
        String withdrawalId = withdraw(b, 100).text("id");

        for (String path : List.of("/api/v1/transfers/" + transferId, "/api/v1/deposits/" + depositId,
                "/api/v1/withdrawals/" + withdrawalId)) {
            Response asA = api.get(path, a.token());
            assertThat(asA.status()).as(path).isEqualTo(404);
            assertThat(asA.code()).isEqualTo("NOT_FOUND");
            assertThat(api.get(path, b.token()).status()).as(path).isEqualTo(200); // the owner sees it
        }
    }

    // =====================================================================================================
    // 8  a statement is read page by page while new entries are posted
    // =====================================================================================================
    @Test
    @Order(8)
    @DisplayName("8: paging while entries are posted: nothing twice, nothing earlier missing (API-5)")
    void pagingWhileEntriesArePosted() throws Exception {
        User a = newUser();
        User b = newUser();
        fund(a, 100_000);
        fund(b, 100_000);
        for (int i = 0; i < 20; i++) {
            transfer(a, b, 10);
        }
        List<String> before = new ArrayList<>();
        for (JsonNode entry : entries(a, null)) {
            before.add(entry.get("id").asText());
        }

        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<?> background = writer.submit(() -> {
            for (int i = 0; i < 20; i++) {
                transfer(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, 10);
            }
        });

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Response page = api.get("/api/v1/wallets/me/entries?limit=3" + (cursor == null ? "" : "&cursor=" + cursor), a.token());
            assertThat(page.status()).isEqualTo(200);
            page.body().get("items").forEach(entry -> seen.add(entry.get("id").asText()));
            cursor = page.body().get("nextCursor").isNull() ? null : page.body().get("nextCursor").asText();
            Thread.sleep(20);
        } while (cursor != null);
        background.get(60, TimeUnit.SECONDS);
        writer.shutdown();

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(before);
    }

    // =====================================================================================================
    // 9  the bank sends the webhook of a succeeded deposit three times
    // =====================================================================================================
    @Test
    @Order(9)
    @DisplayName("9: three copies of the webhook: the wallet is credited once (RES-6, DEP-3)")
    void duplicateWebhooks() throws Exception {
        BANK.webhookCopies = 3;
        User a = newUser();
        String id = deposit(a, 5000).text("id");
        awaitStatus(a, "/api/v1/deposits/" + id, "SUCCEEDED");
        Thread.sleep(1000); // let every copy arrive
        assertThat(balance(a)).isEqualTo(5000);
        assertThat(entries(a, "DEPOSIT")).hasSize(1);
    }

    // =====================================================================================================
    // 10  a webhook with a wrong signature, and one with a timestamp that is 10 minutes old
    // =====================================================================================================
    @Test
    @Order(10)
    @DisplayName("10: wrong signature or 10-minute-old timestamp: 401 INVALID_SIGNATURE, nothing changes (RES-9)")
    void invalidWebhooks() throws Exception {
        BANK.settle = false; // the deposit stays PENDING at the bank
        User a = newUser();
        String id = deposit(a, 5000).text("id");
        String body = webhookBody(id, "CHARGE", 5000, "SUCCEEDED");

        Response wrongSignature = postWebhook(body, Instant.now().getEpochSecond(), "not-the-secret");
        assertThat(wrongSignature.status()).isEqualTo(401);
        assertThat(wrongSignature.code()).isEqualTo("INVALID_SIGNATURE");

        Response tooOld = postWebhook(body, Instant.now().minusSeconds(600).getEpochSecond(), FakeBank.SECRET);
        assertThat(tooOld.status()).isEqualTo(401);
        assertThat(tooOld.code()).isEqualTo("INVALID_SIGNATURE");

        assertThat(api.get("/api/v1/deposits/" + id, a.token()).text("status")).isEqualTo("PENDING");
        assertThat(balance(a)).isZero();
    }

    // =====================================================================================================
    // 11  the bank creates a payout, holds back the response and sends no webhook
    // =====================================================================================================
    @Test
    @Order(11)
    @DisplayName("11: hang and no webhook: no money released after the timeout; reconciliation finishes it (RES-4, RES-7)")
    void hangingBank() throws Exception {
        User a = newUser();
        fund(a, 10_000);
        BANK.hang = true;
        BANK.webhookCopies = 0;
        String id = withdraw(a, 1000).text("id");

        Thread.sleep(1500); // past the client timeout of 1 second
        assertThat(api.get("/api/v1/withdrawals/" + id, a.token()).text("status")).isEqualTo("PENDING");
        assertThat(wallet(a).get("reserved").asLong()).isEqualTo(1000); // still reserved, not given back
        assertThat(balance(a)).isEqualTo(9000);

        awaitStatus(a, "/api/v1/withdrawals/" + id, "SUCCEEDED");
        assertThat(wallet(a).get("reserved").asLong()).isZero();
        assertThat(balance(a)).isEqualTo(9000);
        assertIntegrity();
    }

    // =====================================================================================================
    // 12  a webhook and the reconciliation job apply the same result at the same moment
    // =====================================================================================================
    @Test
    @Order(12)
    @DisplayName("12: webhook and reconciliation at the same moment: applied once (RES-6)")
    void webhookAndReconciliationTogether() throws Exception {
        BANK.webhookCopies = 0;
        User a = newUser();
        String id = deposit(a, 7000).text("id");
        String body = webhookBody(id, "CHARGE", 7000, "SUCCEEDED");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boolean viaWebhook = i % 2 == 0;
            tasks.add(pool.submit(() -> {
                start.await();
                if (viaWebhook) {
                    assertThat(postWebhook(body, Instant.now().getEpochSecond(), FakeBank.SECRET).status()).isEqualTo(200);
                } else { // exactly what the reconciliation job calls
                    operationService.applyResult(OperationKind.DEPOSIT, UUID.fromString(id), "SUCCEEDED", null);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> task : tasks) {
            task.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(balance(a)).isEqualTo(7000);
        assertThat(entries(a, "DEPOSIT")).hasSize(1);
        assertIntegrity();
    }

    // =====================================================================================================
    // 13  three withdrawals while the bank is down, then the bank returns
    // =====================================================================================================
    @Test
    @Order(13)
    @DisplayName("13: bank down: every request answered with 202; all three become final later (RES-1, RES-3)")
    void bankDown() throws Exception {
        User a = newUser();
        fund(a, 10_000);
        BANK.down = true;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();
            Response response = withdraw(a, 1000);
            assertThat(response.status()).isEqualTo(202);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            ids.add(response.text("id"));
        }
        Thread.sleep(1000);
        for (String id : ids) {
            assertThat(api.get("/api/v1/withdrawals/" + id, a.token()).text("status")).isEqualTo("PENDING");
        }

        BANK.down = false;
        for (String id : ids) {
            awaitStatus(a, "/api/v1/withdrawals/" + id, "SUCCEEDED");
        }
        assertThat(balance(a)).isEqualTo(7000);
        assertIntegrity();
    }

    // =====================================================================================================
    // 14  the bank declines a payout
    // =====================================================================================================
    @Test
    @Order(14)
    @DisplayName("14: declined payout: FAILED, money back, WITHDRAWAL_REVERSAL, not in sentToday (WDR-3, MNY-3)")
    void declinedPayout() {
        User a = newUser();
        fund(a, 10_000);
        BANK.decline = true;
        String id = withdraw(a, 2000).text("id");
        awaitStatus(a, "/api/v1/withdrawals/" + id, "FAILED");

        JsonNode w = wallet(a);
        assertThat(w.get("balance").asLong()).isEqualTo(10_000);
        assertThat(w.get("reserved").asLong()).isZero();
        assertThat(w.get("sentToday").asLong()).isZero();
        List<JsonNode> reversals = entries(a, "WITHDRAWAL_REVERSAL");
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).get("amount").asLong()).isEqualTo(2000);
        assertIntegrity();
    }

    // =====================================================================================================
    // 15  transfers complete while Kafka is unavailable, then Kafka returns
    // =====================================================================================================
    @Test
    @Order(15)
    @DisplayName("15: Kafka unavailable: transfers succeed at normal speed; afterwards every event is on the topic (EVT-1, EVT-3)")
    void kafkaUnavailable() {
        User a = newUser();
        User b = newUser();
        fund(a, 10_000);
        List<String> transferIds = new ArrayList<>();

        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            for (int i = 0; i < 3; i++) {
                long started = System.nanoTime();
                Response response = transfer(a, b, 100);
                assertThat(response.status()).isEqualTo(201);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                transferIds.add(response.text("id"));
            }
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "acceptance-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Set<String> published = ConcurrentHashMap.newKeySet();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("wallet.events"));
            await().atMost(Duration.ofSeconds(90)).pollInSameThread().until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains("\"transfer.completed\"")) {
                        published.add(record.key());
                    }
                }
                return published.containsAll(transferIds);
            });
        }
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    User newUser() {
        UUID id = UUID.randomUUID();
        String username = "u" + id.toString().replace("-", "").substring(0, 12);
        String token = TOKENS.mint(id, username, "User " + username, List.of("USER"));
        Response response = api.post("/api/v1/wallets", token, null, null);
        assertThat(response.status()).isEqualTo(201);
        return new User(token, response.text("id"));
    }

    Response transfer(User from, User to, long amount) {
        return api.post("/api/v1/transfers", from.token(), UUID.randomUUID().toString(),
                Map.of("toWalletId", to.walletId(), "amount", amount, "note", "test"));
    }

    Response deposit(User user, long amount) {
        Response response = api.post("/api/v1/deposits", user.token(), UUID.randomUUID().toString(), Map.of("amount", amount));
        assertThat(response.status()).as(response.toString()).isEqualTo(202);
        return response;
    }

    Response withdraw(User user, long amount) {
        Response response = api.post("/api/v1/withdrawals", user.token(), UUID.randomUUID().toString(), Map.of("amount", amount));
        assertThat(response.status()).as(response.toString()).isEqualTo(202);
        return response;
    }

    /** Adds money with deposits of at most 200,000 cents (MNY-2) and waits until each has SUCCEEDED. */
    void fund(User user, long amount) {
        while (amount > 0) {
            long part = Math.min(amount, 200_000);
            String id = deposit(user, part).text("id");
            awaitStatus(user, "/api/v1/deposits/" + id, "SUCCEEDED");
            amount -= part;
        }
    }

    void awaitStatus(User user, String path, String status) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200))
                .until(() -> status.equals(api.get(path, user.token()).text("status")));
    }

    JsonNode wallet(User user) {
        return api.get("/api/v1/wallets/me", user.token()).body();
    }

    long balance(User user) {
        return wallet(user).get("balance").asLong();
    }

    List<JsonNode> entries(User user, String type) {
        Response response = api.get("/api/v1/wallets/me/entries?limit=100" + (type == null ? "" : "&type=" + type), user.token());
        List<JsonNode> list = new ArrayList<>();
        response.body().get("items").forEach(list::add);
        return list;
    }

    void assertIntegrity() {
        Response report = api.get("/api/v1/admin/integrity", adminToken);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().get("ok").asBoolean()).as(report.toString()).isTrue();
    }

    String webhookBody(String reference, String type, long amount, String status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bankTransactionId", "bk-test");
        body.put("reference", reference);
        body.put("type", type);
        body.put("amount", amount);
        body.put("status", status);
        body.put("reason", null);
        return Api.JSON.writeValueAsString(body);
    }

    Response postWebhook(String body, long timestamp, String secret) {
        String ts = Long.toString(timestamp);
        return api.send("POST", "/internal/webhooks/bank", null,
                Map.of("X-Bank-Timestamp", ts, "X-Bank-Signature", FakeBank.sign(secret, ts + "." + body)), body);
    }

    /** Starts n copies of the task at the same moment and returns every response. */
    List<Response> concurrently(int n, Callable<Response> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return responses;
    }

    static long count(List<Response> responses, int status, String code) {
        return responses.stream()
                .filter(r -> r.status() == status && (code == null || code.equals(r.code())))
                .count();
    }
}
