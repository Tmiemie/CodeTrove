package com.codetrove.eventing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.codetrove.common.api.TraceContext;
import com.codetrove.common.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {

    public static final String MR_EVENTS_TOPIC = "codetrove.mr.events.v1";
    public static final String CURATOR_COMMANDS_TOPIC = "codetrove.curator.commands.v1";
    public static final String CURATOR_RESULTS_TOPIC = "codetrove.curator.results.v1";
    public static final String ASSAY_COMMANDS_TOPIC = "codetrove.assay.commands.v1";
    public static final String ASSAY_RESULTS_TOPIC = "codetrove.assay.results.v1";
    private final JdbcTemplate jdbcTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public OutboxService(
        JdbcTemplate jdbcTemplate,
        SnowflakeIdGenerator idGenerator,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    public String append(
        String eventType,
        String aggregateType,
        long aggregateId,
        long aggregateVersion,
        String topic,
        String eventKey,
        String producer,
        Map<String, Object> data
    ) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.normalizeOrCreate(null);
        }
        DomainEvent event = new DomainEvent(
            eventId,
            eventType,
            1,
            now,
            producer,
            traceId,
            new DomainEvent.Aggregate(aggregateType, Long.toString(aggregateId), aggregateVersion),
            Map.copyOf(data)
        );
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_outbox_event
                (id, event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
                 schema_version, topic_name, event_key, payload, trace_id, status, attempts,
                 available_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
            """,
            idGenerator.nextId(),
            eventId,
            eventType,
            aggregateType,
            Long.toString(aggregateId),
            aggregateVersion,
            1,
            topic,
            eventKey,
            serialize(event),
            traceId,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return eventId;
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Domain event cannot be serialized", exception);
        }
    }
}
