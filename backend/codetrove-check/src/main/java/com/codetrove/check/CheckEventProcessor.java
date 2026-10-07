package com.codetrove.check;

import java.util.LinkedHashMap;
import java.util.Map;

import com.codetrove.eventing.DomainEvent;
import com.codetrove.eventing.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckEventProcessor {

    static final String CONSUMER_NAME = "codetrove-check-v1";
    private final ObjectMapper objectMapper;
    private final CheckRepository checkRepository;
    private final OutboxService outboxService;

    public CheckEventProcessor(
        ObjectMapper objectMapper,
        CheckRepository checkRepository,
        OutboxService outboxService
    ) {
        this.objectMapper = objectMapper;
        this.checkRepository = checkRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public void process(String payload) {
        DomainEvent event = parse(payload);
        if (event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported event schema version");
        }
        if (!checkRepository.markConsumed(CONSUMER_NAME, event.eventId(), event.eventType())) {
            return;
        }
        switch (event.eventType()) {
            case "mr.created", "mr.head-updated" -> activateSuite(event.data());
            case "mr.closed", "mr.merged" -> checkRepository.deactivateSuites(
                requiredLong(event.data(), "mr_id"),
                event.eventType().equals("mr.merged") ? "MR_MERGED" : "MR_CLOSED"
            );
            case "assay.execution-started" -> startRun(event.data(), "ASSAY");
            case "assay.execution-completed" -> completeRun(event.data(), "ASSAY");
            case "curator.review-started" -> startRun(event.data(), "CURATOR");
            case "curator.review-completed", "curator.review-skipped" -> completeRun(
                event.data(),
                "CURATOR"
            );
            default -> {
            }
        }
    }

    private void activateSuite(Map<String, Object> data) {
        long mergeRequestId = requiredLong(data, "mr_id");
        String headCommit = requiredString(data, "head_commit");
        CheckRepository.SuiteActivation activation = checkRepository.activateSuite(
            mergeRequestId,
            headCommit
        );
        if (!activation.created()) {
            return;
        }
        if (activation.curatorEnabled()) {
            appendCommand(
                "curator.review-requested",
                OutboxService.CURATOR_COMMANDS_TOPIC,
                activation.curatorRunId(),
                mergeRequestId,
                data
            );
        }
        if (activation.assayEnabled()) {
            appendCommand(
                "assay.execution-requested",
                OutboxService.ASSAY_COMMANDS_TOPIC,
                activation.assayRunId(),
                mergeRequestId,
                data
            );
        }
    }

    private void appendCommand(
        String eventType,
        String topic,
        long checkRunId,
        long mergeRequestId,
        Map<String, Object> data
    ) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("repository_id", requiredString(data, "repository_id"));
        command.put("mr_id", Long.toString(mergeRequestId));
        command.put("mr_iid", requiredLong(data, "mr_iid"));
        command.put("base_commit", requiredString(data, "base_commit"));
        command.put("head_commit", requiredString(data, "head_commit"));
        command.put("check_run_id", Long.toString(checkRunId));
        command.put("attempt", 1);
        outboxService.append(
            eventType,
            "MERGE_REQUEST",
            mergeRequestId,
            eventVersion(data),
            topic,
            requiredString(data, "repository_id") + ":" + requiredLong(data, "mr_iid"),
            "codetrove-check",
            command
        );
    }

    private long eventVersion(Map<String, Object> data) {
        Object sequence = data.get("change_sequence");
        return sequence == null ? 1L : Long.parseLong(sequence.toString());
    }

    private void startRun(Map<String, Object> data, String expectedType) {
        String checkType = requiredString(data, "check_type");
        if (!expectedType.equals(checkType)) {
            throw new IllegalArgumentException("Check type does not match event topic");
        }
        checkRepository.startRun(
            requiredLong(data, "check_run_id"),
            requiredLong(data, "mr_id"),
            requiredString(data, "head_commit"),
            expectedType
        );
    }

    private void completeRun(Map<String, Object> data, String expectedType) {
        String status = requiredString(data, "status");
        if (!java.util.Set.of("SUCCESS", "FAILED", "SKIPPED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("Unsupported terminal check status");
        }
        long runId = requiredLong(data, "check_run_id");
        long mergeRequestId = requiredLong(data, "mr_id");
        String headCommit = requiredString(data, "head_commit");
        String checkType = requiredString(data, "check_type");
        if (!expectedType.equals(checkType)) {
            throw new IllegalArgumentException("Check type does not match event topic");
        }
        Object conclusion = data.get("conclusion");
        checkRepository.completeRun(
            runId,
            mergeRequestId,
            headCommit,
            expectedType,
            status,
            conclusion == null ? null : conclusion.toString()
        );
    }

    private DomainEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, DomainEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid domain event payload", exception);
        }
    }

    private long requiredLong(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalArgumentException("Missing event field: " + name);
    }

    private String requiredString(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + name);
        }
        return value.toString();
    }
}
