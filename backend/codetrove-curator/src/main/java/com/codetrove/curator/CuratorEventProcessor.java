package com.codetrove.curator;

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
import org.springframework.transaction.annotation.Transactional;

@Service
public class CuratorEventProcessor {

    private final ObjectMapper objectMapper;
    private final CuratorRepository repository;
    private final MergeRequestReviewAccessService reviewAccessService;
    private final UnifiedDiffAddedLineParser diffParser;
    private final CuratorReviewOrchestrator orchestrator;
    private final OutboxService outboxService;

    CuratorEventProcessor(
        ObjectMapper objectMapper,
        CuratorRepository repository,
        MergeRequestReviewAccessService reviewAccessService,
        UnifiedDiffAddedLineParser diffParser,
        CuratorReviewOrchestrator orchestrator,
        OutboxService outboxService
    ) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.reviewAccessService = reviewAccessService;
        this.diffParser = diffParser;
        this.orchestrator = orchestrator;
        this.outboxService = outboxService;
    }

    @Transactional
    public void process(String payload) {
        DomainEvent event = parse(payload);
        if (event.schemaVersion() != 1 || !"curator.review-requested".equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported Curator event");
        }
        if (!repository.markConsumed(event.eventId(), event.eventType())) {
            return;
        }
        ReviewCommand command = command(event.data());
        CuratorRepository.ReviewTaskRecord existing = repository.findTask(
            command.mergeRequestId(),
            command.headCommit()
        ).orElse(null);
        if (existing != null) {
            return;
        }
        long taskId = repository.createRunningTask(
            command.repositoryId(),
            command.mergeRequestId(),
            command.headCommit(),
            command.checkRunId(),
            command.attempt()
        );
        appendStarted(taskId, command);
        try {
            MergeRequestReviewAccessService.ReviewDiff diff = reviewAccessService.requireCurrentDiff(
                command.repositoryId(),
                command.mergeRequestId(),
                command.headCommit()
            );
            ReviewInput input = diffParser.parse(diff);
            if (input.truncated()) {
                skip(taskId, command, "DIFF_TRUNCATED");
                return;
            }
            if (input.addedLines().isEmpty()) {
                skip(taskId, command, "NO_REVIEWABLE_CHANGE");
                return;
            }
            List<ReviewFinding> findings = orchestrator.review(input);
            List<Long> findingIds = repository.saveFindings(taskId, findings);
            for (ReviewFinding finding : findings) {
                reviewAccessService.createSystemReviewComment(
                    command.repositoryId(),
                    command.mergeRequestId(),
                    command.headCommit(),
                    finding.filePath(),
                    finding.lineNumber(),
                    renderComment(finding),
                    command.headCommit() + ":" + finding.fingerprint()
                );
            }
            String conclusion = findings.isEmpty() ? "NO_FINDINGS" : "FINDINGS_PRESENT";
            repository.completeTask(taskId, "SUCCESS", conclusion, findings.size());
            appendCompleted(taskId, command, findingIds, findings, conclusion);
        } catch (CuratorSkillUnavailableException exception) {
            skip(taskId, command, "SKILL_UNAVAILABLE");
        } catch (BusinessException exception) {
            if (exception.errorCode() != ErrorCode.CHECK_STALE
                && exception.errorCode() != ErrorCode.MR_HEAD_CHANGED
                && exception.errorCode() != ErrorCode.MR_NOT_OPEN) {
                throw exception;
            }
            cancel(taskId, command, "MR_STALE");
        }
    }

    private void appendStarted(long taskId, ReviewCommand command) {
        Map<String, Object> data = base(taskId, command);
        data.put("status", "RUNNING");
        append("curator.review-started", taskId, command, data);
    }

    private void appendCompleted(
        long taskId,
        ReviewCommand command,
        List<Long> findingIds,
        List<ReviewFinding> findings,
        String conclusion
    ) {
        Map<String, Object> data = base(taskId, command);
        data.put("status", "SUCCESS");
        data.put("conclusion", conclusion);
        data.put("finding_ids", findingIds.stream().map(String::valueOf).toList());
        data.put("summary", summary(findings));
        data.put("report_version", 1);
        append("curator.review-completed", taskId, command, data);
    }

    private void skip(long taskId, ReviewCommand command, String reason) {
        repository.completeTask(taskId, "SKIPPED", reason, 0);
        Map<String, Object> data = base(taskId, command);
        data.put("status", "SKIPPED");
        data.put("conclusion", reason);
        data.put("reason_code", reason);
        append("curator.review-skipped", taskId, command, data);
    }

    private void cancel(long taskId, ReviewCommand command, String reason) {
        repository.completeTask(taskId, "CANCELLED", reason, 0);
        Map<String, Object> data = base(taskId, command);
        data.put("status", "CANCELLED");
        data.put("conclusion", reason);
        data.put("reason_code", reason);
        append("curator.review-skipped", taskId, command, data);
    }

    private Map<String, Object> base(long taskId, ReviewCommand command) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("review_task_id", Long.toString(taskId));
        data.put("repository_id", Long.toString(command.repositoryId()));
        data.put("mr_id", Long.toString(command.mergeRequestId()));
        data.put("mr_iid", command.mergeRequestIid());
        data.put("head_commit", command.headCommit());
        data.put("check_run_id", Long.toString(command.checkRunId()));
        data.put("check_type", "CURATOR");
        data.put("attempt", command.attempt());
        return data;
    }

    private Map<String, Integer> summary(List<ReviewFinding> findings) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (String severity : List.of("CRITICAL", "ERROR", "WARNING", "INFO")) {
            summary.put(
                severity.toLowerCase(java.util.Locale.ROOT),
                (int) findings.stream().filter(finding -> severity.equals(finding.severity())).count()
            );
        }
        summary.put("finding_count", findings.size());
        return summary;
    }

    private String renderComment(ReviewFinding finding) {
        return "**CodeCurator static review · " + finding.severity() + " · "
            + finding.ruleId() + "**\n\n"
            + finding.message() + "\n\n"
            + "Evidence: " + finding.evidence() + "\n\n"
            + "Suggestion: " + finding.suggestion();
    }

    private void append(
        String eventType,
        long taskId,
        ReviewCommand command,
        Map<String, Object> data
    ) {
        outboxService.append(
            eventType,
            "REVIEW_TASK",
            taskId,
            command.attempt(),
            OutboxService.CURATOR_RESULTS_TOPIC,
            command.repositoryId() + ":" + command.mergeRequestIid(),
            "codetrove-curator",
            data
        );
    }

    private ReviewCommand command(Map<String, Object> data) {
        return new ReviewCommand(
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
            throw new IllegalArgumentException("Invalid Curator event payload", exception);
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
        throw new IllegalArgumentException("Missing Curator event field: " + name);
    }

    private String requiredString(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing Curator event field: " + name);
        }
        return value.toString();
    }

    private record ReviewCommand(
        long repositoryId,
        long mergeRequestId,
        int mergeRequestIid,
        String headCommit,
        long checkRunId,
        int attempt
    ) {
    }
}
