package com.fundaccounting.trades;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kafka in and out.
 * <ul>
 *   <li>Outbox relay: publishes trade events written by TradeService, in order, at least once. Consumers are
 *       idempotent (they key on trade id + version), so a re-send after a crash is harmless.</li>
 *   <li>Period events from the accounting service: keep the local copy of closed periods current.</li>
 * </ul>
 */
@Component
public class Messaging {
    private static final Logger log = LoggerFactory.getLogger(Messaging.class);
    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TradeService trades;
    private final ObjectMapper json;

    public Messaging(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TradeService trades, ObjectMapper json) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.trades = trades;
        this.json = json;
    }

    private record OutboxRow(long id, String topic, String key, String payload) {}

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        // SKIP LOCKED lets several instances relay in parallel without sending the same row twice.
        List<OutboxRow> rows = jdbc.sql("""
                SELECT id, topic, message_key, payload::text FROM outbox WHERE published_at IS NULL
                ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED""")
                .query((rs, i) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        for (OutboxRow r : rows) {
            try {
                kafka.send(r.topic(), r.key(), r.payload()).get(10, TimeUnit.SECONDS);   // wait for the broker's ack
            } catch (Exception e) {
                log.warn("Kafka publish failed for outbox row {}; will retry: {}", r.id(), e.toString());
                return;  // keep order: stop at the first failure, retry on the next run
            }
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id").param("id", r.id()).update();
        }
    }

    @KafkaListener(topics = "period-events", groupId = "trade-service")
    public void onPeriodEvent(String message) {
        JsonNode e = json.readTree(message);
        String closed = e.path("closedThrough").isNull() ? null : e.path("closedThrough").asString(null);
        trades.periodClosed(e.path("fundId").asString(), closed == null ? null : LocalDate.parse(closed));
    }
}
