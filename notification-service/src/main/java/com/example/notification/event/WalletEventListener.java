package com.example.notification.event;

import com.example.notification.common.Time;
import com.example.notification.notification.NotificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** NTF-1: consumes wallet.events and creates the notifications of Section 5.8. */
@Component
public class WalletEventListener {

    private static final Logger log = LoggerFactory.getLogger(WalletEventListener.class);

    /** A notification to create: who gets it and what it says. */
    record Planned(UUID userId, String text) {
    }

    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NotificationRepository notifications;
    private final KafkaTemplate<String, String> kafka;
    private final Stats stats;
    private final String deadLetterTopic;

    public WalletEventListener(ObjectMapper objectMapper, JdbcClient jdbc, TransactionTemplate tx,
                               NotificationRepository notifications, KafkaTemplate<String, String> kafka,
                               Stats stats, @Value("${app.kafka.dead-letter-topic}") String deadLetterTopic) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.tx = tx;
        this.notifications = notifications;
        this.kafka = kafka;
        this.stats = stats;
        this.deadLetterTopic = deadLetterTopic;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        UUID eventId;
        String type;
        UUID referenceId;
        List<Planned> planned;
        try {
            JsonNode event = objectMapper.readTree(record.value());
            if (event == null || !event.isObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            // EVT-5: an unknown version is ignored
            if (event.path("version").asInt(-1) != 1) {
                log.info("Ignoring event with unknown version: {}", event.path("version"));
                return;
            }
            eventId = UUID.fromString(event.required("eventId").asText());
            type = event.required("type").asText();
            referenceId = UUID.fromString(event.required("aggregateId").asText());
            planned = plan(type, event.required("payload"));
        } catch (Exception e) {
            deadLetter(record, e); // NTF-4
            return;
        }
        if (planned == null) {
            log.info("Ignoring event of unknown type {}", type); // EVT-5
            return;
        }

        // NTF-2: the event ID is recorded in the same transaction as the notifications.
        // A second delivery of the same event finds the ID and creates nothing.
        Boolean isNew = tx.execute(status -> {
            int inserted = jdbc.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (:id, :at) ON CONFLICT DO NOTHING")
                    .param("id", eventId)
                    .param("at", Time.now())
                    .update();
            if (inserted == 0) {
                return false;
            }
            for (Planned p : planned) {
                notifications.insert(eventId, p.userId(), type, p.text(), referenceId);
            }
            return true;
        });
        if (Boolean.TRUE.equals(isNew)) {
            stats.eventsProcessed.incrementAndGet();
            log.info("Event {} ({}) created {} notification(s)", eventId, type, planned.size());
        } else {
            stats.duplicatesIgnored.incrementAndGet();
            log.info("Event {} ({}) was a duplicate", eventId, type);
        }
    }

    /** Section 5.8: the notifications of each event type; null for a type we do not know. */
    private static List<Planned> plan(String type, JsonNode p) {
        return switch (type) {
            case "transfer.completed" -> {
                String amount = Money.format(p.required("amount").asLong());
                JsonNode from = p.required("from");
                JsonNode to = p.required("to");
                yield List.of(
                        new Planned(userId(from), "You sent " + amount + " to " + to.required("displayName").asText()),
                        new Planned(userId(to), "You received " + amount + " from " + from.required("displayName").asText()));
            }
            case "deposit.succeeded" -> owner(p, "Your deposit of %s is complete");
            case "deposit.failed" -> owner(p, "Your deposit of %s failed");
            case "withdrawal.requested" -> owner(p, "Your withdrawal of %s is in progress");
            case "withdrawal.succeeded" -> owner(p, "Your withdrawal of %s is complete");
            case "withdrawal.failed" -> owner(p, "Your withdrawal of %s failed and the money is back in your wallet");
            default -> null;
        };
    }

    private static List<Planned> owner(JsonNode payload, String template) {
        return List.of(new Planned(userId(payload),
                String.format(template, Money.format(payload.required("amount").asLong()))));
    }

    private static UUID userId(JsonNode node) {
        return UUID.fromString(node.required("userId").asText());
    }

    /** NTF-4: a message that cannot be parsed goes to wallet.events.dlt with its key and value. */
    private void deadLetter(ConsumerRecord<String, String> record, Exception reason) {
        try {
            kafka.send(deadLetterTopic, record.key(), record.value()).get(10, TimeUnit.SECONDS);
            stats.deadLettered.incrementAndGet();
            log.warn("Message at offset {} cannot be parsed ({}); written to {}", record.offset(),
                    reason.getMessage(), deadLetterTopic);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write to the dead-letter topic", e);
        }
    }
}
