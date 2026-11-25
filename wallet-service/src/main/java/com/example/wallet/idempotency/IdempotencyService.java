package com.example.wallet.idempotency;

import com.example.wallet.common.Retry;
import com.example.wallet.common.Time;
import com.example.wallet.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Section 8.2. The operation and its idempotency record are written in ONE database transaction
 * (IDM-4), so a repeated request either sees the stored response or nothing happened at all.
 */
@Service
public class IdempotencyService {

    /** What the operation answered; stored and replayed for a repeated key. */
    public record StoredResponse(int status, Object body, String location) {
    }

    private record Record(String requestHash, Integer statusCode, String responseBody, String location) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> execute(UUID userId, String endpoint, String key, Object request,
                                          Supplier<StoredResponse> operation) {
        // IDM-1
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency key required",
                    "The header Idempotency-Key must be 1 to 64 characters.");
        }
        String requestHash = sha256(toJson(request));

        Optional<Record> existing = find(userId, endpoint, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        try {
            return Retry.onConflict(() -> tx.execute(status -> {
                // Claim the key first. A second request with the same key now waits on this row
                // until we commit (then it replays our response) or roll back (then it runs normally).
                jdbc.sql("""
                        INSERT INTO idempotency_records (user_id, endpoint, idem_key, request_hash, created_at)
                        VALUES (:userId, :endpoint, :key, :hash, :createdAt)
                        """)
                        .param("userId", userId)
                        .param("endpoint", endpoint)
                        .param("key", key)
                        .param("hash", requestHash)
                        .param("createdAt", Time.now())
                        .update();

                // If the operation throws (e.g. INSUFFICIENT_FUNDS), everything rolls back,
                // the key is not stored and can be used again (IDM-3).
                StoredResponse response = operation.get();
                String body = toJson(response.body());

                jdbc.sql("""
                        UPDATE idempotency_records SET status_code = :status, response_body = :body, location = :location
                        WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key
                        """)
                        .param("status", response.status())
                        .param("body", body)
                        .param("location", response.location())
                        .param("userId", userId)
                        .param("endpoint", endpoint)
                        .param("key", key)
                        .update();
                return build(response.status(), body, response.location(), false);
            }));
        } catch (DuplicateKeyException e) {
            // Another request with the same key committed first (IDM-6)
            return find(userId, endpoint, key)
                    .filter(r -> r.statusCode() != null)
                    .map(r -> replay(r, requestHash))
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                            "Request in progress", "A request with the same key is still running.").retryAfter(1));
        }
    }

    /** IDM-2 and IDM-5 */
    private ResponseEntity<String> replay(Record record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                    "The key was used before with a different request body.");
        }
        return build(record.statusCode(), record.responseBody(), record.location(), true);
    }

    private Optional<Record> find(UUID userId, String endpoint, String key) {
        return jdbc.sql("""
                SELECT request_hash, status_code, response_body, location FROM idempotency_records
                WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key
                """)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("key", key)
                .query((rs, row) -> new Record(rs.getString("request_hash"),
                        (Integer) rs.getObject("status_code"),
                        rs.getString("response_body"), rs.getString("location")))
                .optional();
    }

    private static ResponseEntity<String> build(int status, String body, String location, boolean replayed) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            response.header("Location", location);
        }
        if (replayed) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(body);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize", e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
