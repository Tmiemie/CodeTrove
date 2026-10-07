package com.codetrove.assay;

import java.util.List;

import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.mergerequest.MergeRequestReviewAccessService;
import com.codetrove.repository.RepositoryAccessService;
import com.codetrove.repository.RepositoryPermission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

@Service
class AssayQueryService {

    private final RepositoryAccessService repositoryAccessService;
    private final MergeRequestReviewAccessService reviewAccessService;
    private final AssayRepository assayRepository;
    private final ObjectMapper objectMapper;

    AssayQueryService(
        RepositoryAccessService repositoryAccessService,
        MergeRequestReviewAccessService reviewAccessService,
        AssayRepository assayRepository,
        ObjectMapper objectMapper
    ) {
        this.repositoryAccessService = repositoryAccessService;
        this.reviewAccessService = reviewAccessService;
        this.assayRepository = assayRepository;
        this.objectMapper = objectMapper;
    }

    ReportView find(long repositoryId, int mergeRequestIid, AuthenticatedUser user) {
        repositoryAccessService.require(repositoryId, user, RepositoryPermission.READ);
        long mergeRequestId = reviewAccessService.requireMergeRequestId(repositoryId, mergeRequestIid);
        AssayRepository.ExecutionRecord execution = assayRepository.findCurrentExecution(
            repositoryId,
            mergeRequestId
        ).orElse(null);
        if (execution == null) {
            return new ReportView(null, List.of());
        }
        List<CaseResultView> cases = assayRepository.findResults(execution.id()).stream()
            .map(result -> new CaseResultView(
                Long.toString(result.id()),
                result.caseKey(),
                result.sourcePath(),
                result.status(),
                result.failureCode(),
                result.durationMs(),
                parseDiff(result.assertionDiff())
            ))
            .toList();
        return new ReportView(new ExecutionView(
            Long.toString(execution.id()),
            execution.headCommit(),
            Long.toString(execution.checkRunId()),
            execution.status(),
            execution.conclusion(),
            execution.attempt(),
            execution.totalCount(),
            execution.passedCount(),
            execution.failedCount(),
            execution.skippedCount()
        ), cases);
    }

    private JsonNode parseDiff(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Assay assertion diff is invalid", exception);
        }
    }

    record ReportView(ExecutionView execution, List<CaseResultView> cases) {
    }

    record ExecutionView(
        String id,
        String headCommit,
        String checkRunId,
        String status,
        String conclusion,
        int attempt,
        int totalCount,
        int passedCount,
        int failedCount,
        int skippedCount
    ) {
    }

    record CaseResultView(
        String id,
        String caseKey,
        String sourcePath,
        String status,
        String failureCode,
        long durationMs,
        JsonNode assertionDiff
    ) {
    }
}
