package com.codetrove.curator;

import java.util.List;
import java.util.Set;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.mergerequest.MergeRequestReviewAccessService;
import com.codetrove.repository.RepositoryAccessService;
import com.codetrove.repository.RepositoryPermission;

import org.springframework.stereotype.Service;

@Service
class CuratorQueryService {

    private static final Set<String> SKILLS = Set.of("LOGIC", "SECURITY");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "ERROR", "CRITICAL");
    private static final Set<String> DISPOSITIONS = Set.of("OPEN", "ACCEPTED", "FALSE_POSITIVE", "FIXED");

    private final RepositoryAccessService repositoryAccessService;
    private final MergeRequestReviewAccessService reviewAccessService;
    private final CuratorRepository curatorRepository;

    CuratorQueryService(
        RepositoryAccessService repositoryAccessService,
        MergeRequestReviewAccessService reviewAccessService,
        CuratorRepository curatorRepository
    ) {
        this.repositoryAccessService = repositoryAccessService;
        this.reviewAccessService = reviewAccessService;
        this.curatorRepository = curatorRepository;
    }

    ReviewView find(
        long repositoryId,
        int mergeRequestIid,
        AuthenticatedUser user,
        String skill,
        String severity,
        String disposition
    ) {
        repositoryAccessService.require(repositoryId, user, RepositoryPermission.READ);
        validateFilter(skill, SKILLS, "skill");
        validateFilter(severity, SEVERITIES, "severity");
        validateFilter(disposition, DISPOSITIONS, "disposition");
        long mergeRequestId = reviewAccessService.requireMergeRequestId(repositoryId, mergeRequestIid);
        CuratorRepository.ReviewTaskRecord task = curatorRepository.findCurrentTask(
            repositoryId,
            mergeRequestId
        ).orElse(null);
        if (task == null) {
            return new ReviewView(null, List.of());
        }
        List<CuratorRepository.FindingRecord> findings = curatorRepository.findCurrentFindings(
            repositoryId,
            mergeRequestId
        ).stream()
            .filter(finding -> skill == null || skill.equals(finding.skill()))
            .filter(finding -> severity == null || severity.equals(finding.severity()))
            .filter(finding -> disposition == null || disposition.equals(finding.disposition()))
            .toList();
        return new ReviewView(
            new TaskView(
                Long.toString(task.id()),
                task.headCommit(),
                Long.toString(task.checkRunId()),
                task.status(),
                task.conclusion(),
                task.attempt(),
                task.findingCount()
            ),
            findings.stream().map(finding -> new FindingView(
                Long.toString(finding.id()),
                finding.skill(),
                finding.severity(),
                finding.ruleId(),
                finding.filePath(),
                finding.side(),
                finding.lineNumber(),
                finding.title(),
                finding.message(),
                finding.evidence(),
                finding.suggestion(),
                finding.disposition()
            )).toList()
        );
    }

    private void validateFilter(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid " + name + " filter");
        }
    }

    record ReviewView(TaskView task, List<FindingView> findings) {
    }

    record TaskView(
        String id,
        String headCommit,
        String checkRunId,
        String status,
        String conclusion,
        int attempt,
        int findingCount
    ) {
    }

    record FindingView(
        String id,
        String skill,
        String severity,
        String ruleId,
        String filePath,
        String side,
        int lineNumber,
        String title,
        String message,
        String evidence,
        String suggestion,
        String disposition
    ) {
    }
}
