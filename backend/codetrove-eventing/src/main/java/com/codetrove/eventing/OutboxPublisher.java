package com.codetrove.eventing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "codetrove.eventing.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 500;
    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration sendTimeout;

    public OutboxPublisher(
        JdbcTemplate jdbcTemplate,
        KafkaTemplate<String, String> kafkaTemplate,
        TransactionTemplate transactionTemplate,
        @Value("${codetrove.eventing.batch-size:20}") int batchSize,
        @Value("${codetrove.eventing.max-attempts:8}") int maxAttempts,
        @Value("${codetrove.eventing.send-timeout:10s}") Duration sendTimeout
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${codetrove.eventing.poll-interval:1000}")
    public void publishReady() {
        List<OutboxRecord> records = transactionTemplate.execute(status -> claimReady());
        if (records == null) {
            return;
        }
        records.forEach(this::publish);
    }

    private List<OutboxRecord> claimReady() {
        List<OutboxRecord> records = jdbcTemplate.query(
            """
            SELECT id, event_id, topic_name, event_key, payload, attempts
            FROM codetrove_outbox_event
            WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6)
            ORDER BY id
            LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            (resultSet, rowNumber) -> new OutboxRecord(
                resultSet.getLong("id"),
                resultSet.getString("event_id"),
                resultSet.getString("topic_name"),
                resultSet.getString("event_key"),
                resultSet.getString("payload"),
                resultSet.getInt("attempts") + 1
            ),
            batchSize
        );
        Instant leaseUntil = Instant.now().plus(sendTimeout.multipliedBy(2)).plusSeconds(5);
        for (OutboxRecord record : records) {
            jdbcTemplate.update(
                """
                UPDATE codetrove_outbox_event
                SET attempts = ?, available_at = ?
                WHERE id = ? AND status = 'PENDING'
                """,
                record.attempts(),
                Timestamp.from(leaseUntil),
                record.id()
            );
        }
        return records;
    }

    private void publish(OutboxRecord record) {
        try {
            kafkaTemplate.send(record.topic(), record.key(), record.payload())
                .get(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                """
                UPDATE codetrove_outbox_event
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6),
                    last_error = NULL
                WHERE id = ? AND status = 'PENDING' AND attempts = ?
                """,
                record.id(),
                record.attempts()
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(record, exception);
        } catch (Exception exception) {
            recordFailure(record, exception);
        }
    }

    private void recordFailure(OutboxRecord record, Exception exception) {
        int attempt = record.attempts();
        boolean failed = attempt >= maxAttempts;
        long delaySeconds = Math.min(300L, 1L << Math.min(attempt, 8));
        Instant availableAt = Instant.now().plusSeconds(delaySeconds);
        String message = sanitize(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
            """
            UPDATE codetrove_outbox_event
            SET status = ?, available_at = ?, last_error = ?
            WHERE id = ? AND status = 'PENDING' AND attempts = ?
            """,
            failed ? "FAILED" : "PENDING",
            Timestamp.from(availableAt),
            message,
            record.id(),
            attempt
        ));
    }

    private String sanitize(String value) {
        String safe = value == null ? "Kafka publish failed" : value.replaceAll("[\\r\\n\\t]", " ");
        return safe.length() <= MAX_ERROR_LENGTH ? safe : safe.substring(0, MAX_ERROR_LENGTH);
    }

    private record OutboxRecord(
        long id,
        String eventId,
        String topic,
        String key,
        String payload,
        int attempts
    ) {
    }
}
