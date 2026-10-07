package com.codetrove.assay;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.eventing.DomainEvent;
import com.codetrove.eventing.OutboxService;
import com.codetrove.mergerequest.MergeRequestReviewAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssayEventProcessor {

    private final ObjectMapper objectMapper;
    private final AssayRepository repository;
    private final MergeRequestReviewAccessService reviewAccessService;
    private final AssayCaseParser caseParser;
    private final AssayHttpExecutor httpExecutor;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    AssayEventProcessor(
        ObjectMapper objectMapper,
        AssayRepository repository,
        MergeRequestReviewAccessService reviewAccessService,
        AssayCaseParser caseParser,
        AssayHttpExecutor httpExecutor,
        OutboxService outboxService,
        TransactionTemplate transactionTemplate
    ) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.reviewAccessService = reviewAccessService;
        this.caseParser = caseParser;
        this.httpExecutor = httpExecutor;
        this.outboxService = outboxService;
        this.transactionTemplate = transactionTemplate;
    }

    public void process(String payload) {
        DomainEvent event = parse(payload);
        if (event.schemaVersion() != 1 || !"assay.execution-requested".equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported Assay event");
        }
        ExecutionCommand command = command(event.data());
        PreparedExecution prepared = transactionTemplate.execute(status -> prepare(event, command));
        if (prepared == null || !prepared.execute()) {
            return;
        }

        List<AssayCaseResult> results;
        String terminalStatus;
        String conclusion;
        try {
            var sources = reviewAccessService.requireCurrentTestCases(
                command.repositoryId(),
                command.mergeRequestId(),
                command.headCommit()
            );
            List<AssayCaseDefinition> definitions = caseParser.parseAll(sources);
            results = httpExecutor.execute(definitions);
            if (definitions.stream().noneMatch(AssayCaseDefinition::enabled)) {
                terminalStatus = "SKIPPED";
                conclusion = "NO_ENABLED_CASES";
            } else if (results.stream().allMatch(result ->
                "PASSED".equals(result.status()) || "SKIPPED".equals(result.status()))) {
                terminalStatus = "SUCCESS";
                conclusion = "ALL_CASES_PASSED";
            } else {
                terminalStatus = "FAILED";
                conclusion = primaryFailure(results);
            }
        } catch (BusinessException exception) {
            if (exception.errorCode() != ErrorCode.CHECK_STALE
                && exception.errorCode() != ErrorCode.MR_HEAD_CHANGED
                && exception.errorCode() != ErrorCode.MR_NOT_OPEN) {
                throw exception;
            }
            results = List.of();
            terminalStatus = "CANCELLED";
            conclusion = "MR_STALE";
        } catch (AssayDefinitionException exception) {
            results = List.of(new AssayCaseResult(
                "definition-validation",
                "testcases",
                "ERROR",
                exception.failureCode(),
                0L,
                List.of(new AssayCaseResult.AssertionDiff(
                    0,
                    "$",
                    "validation",
                    null,
                    null,
                    exception.getMessage()
                ))
            ));
            terminalStatus = "FAILED";
            conclusion = "CASE_DEFINITION_INVALID";
        } catch (RuntimeException exception) {
            results = List.of(new AssayCaseResult(
                "execution-infrastructure",
                "testcases",
                "ERROR",
                "INFRASTRUCTURE_ERROR",
                0L,
                List.of()
            ));
            terminalStatus = "FAILED";
            conclusion = "INFRASTRUCTURE_ERROR";
        }

        List<AssayCaseResult> finalResults = results;
        String finalStatus = terminalStatus;
        String finalConclusion = conclusion;
        transactionTemplate.executeWithoutResult(status -> finish(
            prepared.executionId(),
            prepared.runToken(),
            command,
            finalStatus,
            finalConclusion,
            finalResults
        ));
    }

    private PreparedExecution prepare(DomainEvent event, ExecutionCommand command) {
        AssayRepository.ExecutionRecord existing = repository.findExecution(
            command.mergeRequestId(),
            command.headCommit()
        ).orElse(null);
        if (existing != null && existing.terminal()) {
            return new PreparedExecution(existing.id(), null, false);
        }
        boolean firstDelivery = repository.markConsumed(event.eventId(), event.eventType());
        if (!firstDelivery && existing == null) {
            return new PreparedExecution(0L, null, false);
        }
        long executionId = existing == null
            ? repository.createPending(
                command.repositoryId(),
                command.mergeRequestId(),
                command.headCommit(),
                command.checkRunId(),
                command.attempt()
            )
            : existing.id();
        String runToken = repository.claim(executionId, Duration.ofSeconds(45));
        if (runToken == null) {
            return new PreparedExecution(executionId, null, false);
        }
        appendStarted(executionId, command);
        return new PreparedExecution(executionId, runToken, true);
    }

    private void finish(
        long executionId,
        String runToken,
        ExecutionCommand command,
        String status,
        String conclusion,
        List<AssayCaseResult> results
    ) {
        if (!repository.complete(executionId, runToken, status, conclusion, results)) {
            return;
        }
        if (!"CANCELLED".equals(status)) {
            reviewAccessService.createSystemTestReport(
                command.repositoryId(),
                command.mergeRequestId(),
                command.headCommit(),
                renderReport(status, conclusion, results),
                command.headCommit() + ":assay:" + command.attempt()
            );
        }
        appendCompleted(executionId, command, status, conclusion, results);
    }

    private void appendStarted(long executionId, ExecutionCommand command) {
        Map<String, Object> data = base(executionId, command);
        data.put("status", "RUNNING");
        append("assay.execution-started", executionId, command, data);
    }

    private void appendCompleted(
        long executionId,
        ExecutionCommand command,
        String status,
        String conclusion,
        List<AssayCaseResult> results
    ) {
        Map<String, Object> data = base(executionId, command);
        data.put("status", status);
        data.put("conclusion", conclusion);
        data.put("summary", summary(results));
        data.put("failure_codes", results.stream()
            .map(AssayCaseResult::failureCode)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList());
        data.put("report_id", Long.toString(executionId));
        append("assay.execution-completed", executionId, command, data);
    }

    private Map<String, Object> base(long executionId, ExecutionCommand command) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("execution_id", Long.toString(executionId));
        data.put("repository_id", Long.toString(command.repositoryId()));
        data.put("mr_id", Long.toString(command.mergeRequestId()));
        data.put("mr_iid", command.mergeRequestIid());
        data.put("head_commit", command.headCommit());
        data.put("check_run_id", Long.toString(command.checkRunId()));
        data.put("check_type", "ASSAY");
        data.put("attempt", command.attempt());
        return data;
    }

    private Map<String, Integer> summary(List<AssayCaseResult> results) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("passed", (int) results.stream().filter(result -> "PASSED".equals(result.status())).count());
        summary.put("failed", (int) results.stream()
            .filter(result -> "FAILED".equals(result.status()) || "ERROR".equals(result.status()))
            .count());
        summary.put("skipped", (int) results.stream().filter(result -> "SKIPPED".equals(result.status())).count());
        return summary;
    }

    private String primaryFailure(List<AssayCaseResult> results) {
        return results.stream().map(AssayCaseResult::failureCode)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse("ASSERTION_MISMATCH");
    }

    private String renderReport(
        String status,
        String conclusion,
        List<AssayCaseResult> results
    ) {
        StringBuilder report = new StringBuilder()
            .append("**CodeAssay · ").append(status).append(" · ").append(conclusion).append("**\n\n")
            .append("| Case | Status | Failure | Duration |\n")
            .append("| --- | --- | --- | ---: |\n");
        for (AssayCaseResult result : results) {
            report.append("| `").append(safe(result.caseKey())).append("` | ")
                .append(result.status()).append(" | ")
                .append(result.failureCode() == null ? "-" : result.failureCode())
                .append(" | ").append(result.durationMs()).append(" ms |\n");
            for (AssayCaseResult.AssertionDiff diff : result.assertionDiffs()) {
                report.append("\n- `").append(safe(diff.path())).append("` ")
                    .append(safe(diff.operator())).append(": expected `")
                    .append(safe(diff.expected())).append("`, actual `")
                    .append(safe(diff.actual())).append("` — ")
                    .append(safe(diff.message()));
            }
        }
        String value = report.toString();
        return value.length() <= 10_000 ? value : value.substring(0, 10_000);
    }

    private String safe(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("`", "'")
            .replace("|", "\\|")
            .replace("<", "&lt;")
            .replace("\r", " ")
            .replace("\n", " ");
    }

    private void append(
        String eventType,
        long executionId,
        ExecutionCommand command,
        Map<String, Object> data
    ) {
        outboxService.append(
            eventType,
            "ASSAY_EXECUTION",
            executionId,
            command.attempt(),
            OutboxService.ASSAY_RESULTS_TOPIC,
            command.repositoryId() + ":" + command.mergeRequestIid(),
            "codetrove-assay",
            data
        );
    }

    private ExecutionCommand command(Map<String, Object> data) {
        return new ExecutionCommand(
            requiredLong(data, "repository_id"),
            requiredLong(data, "mr_id"),
            Math.toIntExact(requiredLong(data, "mr_iid")),
            requiredString(data, "head_commit"),
            requiredLong(data, "check_run_id"),
            Math.toIntExact(requiredLong(data, "attempt"))
        );
    }

    private DomainEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, DomainEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid Assay event payload", exception);
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
        throw new IllegalArgumentException("Missing Assay event field: " + name);
    }

    private String requiredString(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing Assay event field: " + name);
        }
        return value.toString();
    }

    private record ExecutionCommand(
        long repositoryId,
        long mergeRequestId,
        int mergeRequestIid,
        String headCommit,
        long checkRunId,
        int attempt
    ) {
    }

    private record PreparedExecution(long executionId, String runToken, boolean execute) {
    }
}
