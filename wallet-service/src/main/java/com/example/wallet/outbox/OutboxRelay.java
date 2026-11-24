package com.example.wallet.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes unsent outbox rows to Kafka in the order they were written (EVT-2).
 * A row is marked as sent only after Kafka confirmed it, so delivery is at least once.
 * While Kafka is down, rows simply wait here; the API is not affected (EVT-3).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long seq, UUID aggregateId, String type, String envelope) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
                       @Value("${app.kafka.topic}") String topic) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${app.kafka.relay-interval-ms}")
    public void publish() {
        List<Row> rows = jdbc.sql("SELECT seq, aggregate_id, type, envelope FROM outbox WHERE sent_at IS NULL ORDER BY seq LIMIT 100")
                .query((rs, i) -> new Row(rs.getLong("seq"), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("type"), rs.getString("envelope")))
                .list();
        for (Row row : rows) {
            try {
                // key = aggregateId: all events of one transfer/deposit/withdrawal go to the same partition, in order
                kafka.send(topic, row.aggregateId().toString(), row.envelope()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Kafka is not available ({}); {} event(s) wait in the outbox", e.getMessage(), rows.size());
                return; // stop here so that the order is kept; try again next time
            }
            jdbc.sql("UPDATE outbox SET sent_at = now() WHERE seq = :seq").param("seq", row.seq()).update();
            log.info("Published {} for {}", row.type(), row.aggregateId());
        }
    }
}
