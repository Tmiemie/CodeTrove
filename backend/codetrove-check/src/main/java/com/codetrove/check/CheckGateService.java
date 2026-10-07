package com.codetrove.check;

import java.util.List;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CheckGateService {

    private final CheckRepository checkRepository;
    private final boolean enabled;

    public CheckGateService(
        CheckRepository checkRepository,
        @Value("${codetrove.check-gate.enabled:true}") boolean enabled
    ) {
        this.checkRepository = checkRepository;
        this.enabled = enabled;
    }

    public void requireBlockingChecksPassed(long mergeRequestId, String headCommit) {
        if (!enabled) {
            return;
        }
        if (!checkRepository.blockingChecksPassed(mergeRequestId, headCommit)) {
            throw new BusinessException(ErrorCode.MR_CHECKS_NOT_PASSED);
        }
    }

    public List<CheckSuiteView> findSuites(long mergeRequestId, boolean includeHistory) {
        return checkRepository.findSuites(mergeRequestId, !includeHistory)
            .stream()
            .map(CheckSuiteView::from)
            .toList();
    }

    public record CheckSuiteView(
        String id,
        String headCommit,
        String status,
        boolean current,
        long version,
        java.time.Instant createdAt,
        java.time.Instant updatedAt,
        List<CheckRunView> runs
    ) {
        static CheckSuiteView from(CheckRepository.CheckSuiteRecord record) {
            return new CheckSuiteView(
                Long.toString(record.id()),
                record.headCommit(),
                record.status(),
                record.current(),
                record.version(),
                record.createdAt(),
                record.updatedAt(),
                record.runs().stream().map(CheckRunView::from).toList()
            );
        }
    }

    public record CheckRunView(
        String id,
        String checkType,
        String name,
        boolean blocking,
        String status,
        String conclusion,
        String detailsUrl,
        int attempt,
        java.time.Instant startedAt,
        java.time.Instant finishedAt
    ) {
        static CheckRunView from(CheckRepository.CheckRunRecord record) {
            return new CheckRunView(
                Long.toString(record.id()),
                record.checkType(),
                record.name(),
                record.blocking(),
                record.status(),
                record.conclusion(),
                record.detailsUrl(),
                record.attempt(),
                record.startedAt(),
                record.finishedAt()
            );
        }
    }
}
