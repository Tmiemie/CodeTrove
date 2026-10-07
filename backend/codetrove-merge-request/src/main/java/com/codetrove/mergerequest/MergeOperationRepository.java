package com.codetrove.mergerequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.codetrove.eventing.OutboxService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MergeOperationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final OutboxService outboxService;

    MergeOperationRepository(JdbcTemplate jdbcTemplate, OutboxService outboxService) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
    }

    Optional<MergeOperationRecord> find(long repositoryId, String idempotencyKey) {
        return jdbcTemplate.query(
            """
            SELECT id, repository_id, merge_request_id, idempotency_key, request_hash,
                   expected_head_commit, target_before_commit, merge_commit, status,
                   merged_by, created_at, completed_at
            FROM codetrove_merge_operation
            WHERE repository_id = ? AND idempotency_key = ?
            """,
            (resultSet, rowNumber) -> {
                Timestamp completed = resultSet.getTimestamp("completed_at");
                return new MergeOperationRecord(
                    resultSet.getLong("id"),
                    resultSet.getLong("repository_id"),
                    resultSet.getLong("merge_request_id"),
                    resultSet.getString("idempotency_key"),
                    resultSet.getString("request_hash"),
                    resultSet.getString("expected_head_commit"),
                    resultSet.getString("target_before_commit"),
                    resultSet.getString("merge_commit"),
                    resultSet.getString("status"),
                    resultSet.getLong("merged_by"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    completed == null ? null : completed.toInstant()
                );
            },
            repositoryId,
            idempotencyKey
        ).stream().findFirst();
    }

    void createPending(
        long id,
        long repositoryId,
        long mergeRequestId,
        String idempotencyKey,
        String requestHash,
        String expectedHead,
        String targetBefore,
        String mergeCommit,
        long mergedBy
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_merge_operation
                (id, repository_id, merge_request_id, idempotency_key, request_hash,
                 expected_head_commit, target_before_commit, merge_commit, status,
                 merged_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            """,
            id,
            repositoryId,
            mergeRequestId,
            idempotencyKey,
            requestHash,
            expectedHead,
            targetBefore,
            mergeCommit,
            mergedBy,
            Timestamp.from(Instant.now())
        );
    }

    void deletePending(long operationId) {
        jdbcTemplate.update(
            "DELETE FROM codetrove_merge_operation WHERE id = ? AND status = 'PENDING'",
            operationId
        );
    }

    @Transactional
    MergeRequestRecord complete(
        MergeOperationRecord operation,
        int iid,
        MergeRequestRepository mergeRequestRepository
    ) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
            UPDATE codetrove_merge_request
            SET status = 'MERGED', merged_by = ?, merged_at = ?, merge_commit = ?,
                version = version + 1, updated_at = ?
            WHERE id = ? AND status = 'OPEN' AND head_commit = ?
            """,
            operation.mergedBy(),
            Timestamp.from(now),
            operation.mergeCommit(),
            Timestamp.from(now),
            operation.mergeRequestId(),
            operation.expectedHeadCommit()
        );
        if (updated == 0) {
            MergeRequestRecord current = mergeRequestRepository.findByIid(operation.repositoryId(), iid)
                .orElse(null);
            if (current == null || current.status() != MergeRequestStatus.MERGED
                || !operation.mergeCommit().equals(current.mergeCommit())) {
                throw new IllegalStateException("Merge request final state cannot be recovered");
            }
            markSucceeded(operation.id(), current.mergedAt());
            return current;
        }
        markSucceeded(operation.id(), now);
        MergeRequestRecord merged = mergeRequestRepository.findByIid(operation.repositoryId(), iid)
            .orElseThrow(() -> new IllegalStateException("Merged request disappeared"));
        appendMergedEvent(merged, operation);
        return merged;
    }

    private void appendMergedEvent(
        MergeRequestRecord mergeRequest,
        MergeOperationRecord operation
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repository_id", Long.toString(mergeRequest.repositoryId()));
        data.put("mr_id", Long.toString(mergeRequest.id()));
        data.put("mr_iid", mergeRequest.iid());
        data.put("source_head_commit", operation.expectedHeadCommit());
        data.put("target_before_commit", operation.targetBeforeCommit());
        data.put("merge_commit", operation.mergeCommit());
        data.put("merged_by", Long.toString(operation.mergedBy()));
        data.put("strategy", "MERGE_COMMIT");
        outboxService.append(
            "mr.merged",
            "MERGE_REQUEST",
            mergeRequest.id(),
            mergeRequest.version(),
            OutboxService.MR_EVENTS_TOPIC,
            mergeRequest.repositoryId() + ":" + mergeRequest.iid(),
            "codetrove-merge-request",
            data
        );
    }

    private void markSucceeded(long operationId, Instant completedAt) {
        jdbcTemplate.update(
            """
            UPDATE codetrove_merge_operation
            SET status = 'SUCCEEDED', completed_at = ?
            WHERE id = ?
            """,
            Timestamp.from(completedAt),
            operationId
        );
    }

    record MergeOperationRecord(
        long id,
        long repositoryId,
        long mergeRequestId,
        String idempotencyKey,
        String requestHash,
        String expectedHeadCommit,
        String targetBeforeCommit,
        String mergeCommit,
        String status,
        long mergedBy,
        Instant createdAt,
        Instant completedAt
    ) {
    }
}
