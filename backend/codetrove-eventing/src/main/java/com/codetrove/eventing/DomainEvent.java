package com.codetrove.eventing;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DomainEvent(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("occurred_at") Instant occurredAt,
    String producer,
    @JsonProperty("trace_id") String traceId,
    Aggregate aggregate,
    Map<String, Object> data
) {

    public record Aggregate(String type, String id, long version) {
    }
}
