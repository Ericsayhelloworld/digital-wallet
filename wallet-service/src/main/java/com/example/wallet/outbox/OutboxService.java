package com.example.wallet.outbox;

import com.example.wallet.common.Time;
import com.example.wallet.wallet.Wallet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EVT-1: an event is stored in the outbox table in the SAME database transaction as the business change.
 * If the transaction rolls back, the event disappears with it; if it commits, the relay will publish it.
 */
@Service
public class OutboxService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Must be called inside the transaction of the business operation. */
    public void add(String type, UUID aggregateId, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = Time.now();

        // Section 7: the envelope
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", now);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("payload", payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize event", e);
        }
        jdbc.sql("""
                INSERT INTO outbox (event_id, aggregate_id, type, envelope, created_at)
                VALUES (:eventId, :aggregateId, :type, :envelope, :createdAt)
                """)
                .param("eventId", eventId)
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("envelope", json)
                .param("createdAt", now)
                .update();
    }

    /** The from/to part of transfer.completed. */
    public static Map<String, Object> party(Wallet wallet) {
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("walletId", wallet.id().toString());
        party.put("userId", wallet.userId().toString());
        party.put("displayName", wallet.displayName());
        return party;
    }
}
