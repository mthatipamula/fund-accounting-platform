package com.fundaccounting.accounting;

import com.fundaccounting.accounting.AccountingRepository.TradeEvent;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kafka in and out.
 * <ul>
 *   <li>Trade events from trade-service, stored idempotently by (id, version), so redelivery is harmless.</li>
 *   <li>Outbox relay for period events (close / reopen), which trade-service uses to refuse back-dated trades.</li>
 * </ul>
 */
@Component
public class Messaging {
    private static final Logger log = LoggerFactory.getLogger(Messaging.class);
    public static final String PERIOD_TOPIC = "period-events";
    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final AccountingRepository repo;
    private final ObjectMapper json;

    public Messaging(JdbcClient jdbc, KafkaTemplate<String, String> kafka, AccountingRepository repo, ObjectMapper json) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.repo = repo;
        this.json = json;
    }

    @KafkaListener(topics = "trade-events", groupId = "accounting-service")
    public void onTradeEvent(String message) {
        JsonNode e = json.readTree(message);
        repo.upsertTradeEvent(new TradeEvent(e.path("id").asString(), e.path("version").asInt(), e.path("fundId").asString(),
                e.path("securityId").asString(), e.path("side").asString(), new BigDecimal(e.path("quantity").asString()),
                new BigDecimal(e.path("price").asString()), new BigDecimal(e.path("commission").asString()),
                LocalDate.parse(e.path("tradeDate").asString()), LocalDate.parse(e.path("settleDate").asString()),
                Instant.parse(e.path("recordedAt").asString()), e.path("cancelled").asBoolean()));
    }

    private record OutboxRow(long id, String topic, String key, String payload) {}

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OutboxRow> rows = jdbc.sql("""
                SELECT id, topic, message_key, payload::text FROM outbox WHERE published_at IS NULL
                ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED""")
                .query((rs, i) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        for (OutboxRow r : rows) {
            try {
                kafka.send(r.topic(), r.key(), r.payload()).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.warn("Kafka publish failed for outbox row {}; will retry: {}", r.id(), ex.toString());
                return;
            }
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id").param("id", r.id()).update();
        }
    }
}
