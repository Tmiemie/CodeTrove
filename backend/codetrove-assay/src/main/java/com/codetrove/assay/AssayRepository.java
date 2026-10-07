package com.codetrove.assay;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.codetrove.common.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AssayRepository {

    private static final String CONSUMER_NAME = "codetrove-assay-v1";
    private final JdbcTemplate jdbcTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    AssayRepository(
        JdbcTemplate jdbcTemplate,
        SnowflakeIdGenerator idGenerator,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    boolean markConsumed(String eventId, String eventType) {
        try {
            return jdbcTemplate.update(
                """
                INSERT INTO codetrove_consumed_event
                    (id, consumer_name, event_id, event_type, consumed_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                idGenerator.nextId(),
                CONSUMER_NAME,
                eventId,
                eventType,
                Timestamp.from(Instant.now())
            ) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    Optional<ExecutionRecord> findExecution(long mergeRequestId, String headCommit) {
        return jdbcTemplate.query(
            """
            SELECT id, repository_id, merge_request_id, head_commit, check_run_id,
                   status, conclusion, attempt, run_token, lease_until, total_count, passed_count,
                   failed_count, skipped_count
            FROM codetrove_assay_execution
            WHERE merge_request_id = ? AND head_commit = ?
            """,
            (resultSet, rowNumber) -> mapExecution(resultSet),
            mergeRequestId,
            headCommit
        ).stream().findFirst();
    }

    Optional<ExecutionRecord> findCurrentExecution(long repositoryId, long mergeRequestId) {
        return jdbcTemplate.query(
            """
            SELECT execution.id, execution.repository_id, execution.merge_request_id,
                   execution.head_commit, execution.check_run_id, execution.status,
                   execution.conclusion, execution.attempt, execution.run_token,
                   execution.lease_until, execution.total_count,
                   execution.passed_count, execution.failed_count, execution.skipped_count
            FROM codetrove_assay_execution execution
            JOIN codetrove_merge_request mr ON mr.id = execution.merge_request_id
            WHERE execution.repository_id = ? AND execution.merge_request_id = ?
              AND execution.head_commit = mr.head_commit
            """,
            (resultSet, rowNumber) -> mapExecution(resultSet),
            repositoryId,
            mergeRequestId
        ).stream().findFirst();
    }

    long createPending(
        long repositoryId,
        long mergeRequestId,
        String headCommit,
        long checkRunId,
        int attempt
    ) {
        long id = idGenerator.nextId();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_assay_execution
                (id, repository_id, merge_request_id, head_commit, check_run_id,
                 status, conclusion, attempt, total_count, passed_count, failed_count,
                 skipped_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', NULL, ?, 0, 0, 0, 0, ?, ?)
            """,
            id,
            repositoryId,
            mergeRequestId,
            headCommit,
            checkRunId,
            attempt,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return id;
    }

    String claim(long executionId, java.time.Duration leaseDuration) {
        String runToken = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
            UPDATE codetrove_assay_execution
            SET status = 'RUNNING', run_token = ?, lease_until = ?,
                started_at = COALESCE(started_at, ?), updated_at = ?
            WHERE id = ? AND status IN ('PENDING', 'RUNNING')
            """,
            runToken,
            Timestamp.from(now.plus(leaseDuration)),
            Timestamp.from(now),
            Timestamp.from(now),
            executionId
        );
        return updated == 1 ? runToken : null;
    }

    boolean complete(
        long executionId,
        String runToken,
        String status,
        String conclusion,
        List<AssayCaseResult> results
    ) {
        int total = results.size();
        int passed = (int) results.stream().filter(result -> "PASSED".equals(result.status())).count();
        int failed = (int) results.stream()
            .filter(result -> "FAILED".equals(result.status()) || "ERROR".equals(result.status()))
            .count();
        int skipped = total - passed - failed;
        int updated = jdbcTemplate.update(
            """
            UPDATE codetrove_assay_execution
            SET status = ?, conclusion = ?, run_token = NULL, lease_until = NULL,
                total_count = ?, passed_count = ?,
                failed_count = ?, skipped_count = ?, finished_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ? AND status = 'RUNNING' AND run_token = ?
            """,
            status,
            conclusion,
            total,
            passed,
            failed,
            skipped,
            executionId,
            runToken
        );
        if (updated != 1) {
            return false;
        }
        for (AssayCaseResult result : results) {
            jdbcTemplate.update(
                """
                INSERT INTO codetrove_assay_case_result
                    (id, execution_id, case_key, source_path, status, failure_code,
                     duration_ms, assertion_diff, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                idGenerator.nextId(),
                executionId,
                result.caseKey(),
                result.sourcePath(),
                result.status(),
                result.failureCode(),
                result.durationMs(),
                serialize(result.assertionDiffs()),
                Timestamp.from(Instant.now())
            );
        }
        return true;
    }

    List<CaseResultRecord> findResults(long executionId) {
        return jdbcTemplate.query(
            """
            SELECT id, case_key, source_path, status, failure_code, duration_ms, assertion_diff
            FROM codetrove_assay_case_result
            WHERE execution_id = ? ORDER BY id
            """,
            (resultSet, rowNumber) -> new CaseResultRecord(
                resultSet.getLong("id"),
                resultSet.getString("case_key"),
                resultSet.getString("source_path"),
                resultSet.getString("status"),
                resultSet.getString("failure_code"),
                resultSet.getLong("duration_ms"),
                resultSet.getString("assertion_diff")
            ),
            executionId
        );
    }

    private ExecutionRecord mapExecution(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ExecutionRecord(
            resultSet.getLong("id"),
            resultSet.getLong("repository_id"),
            resultSet.getLong("merge_request_id"),
            resultSet.getString("head_commit"),
            resultSet.getLong("check_run_id"),
            resultSet.getString("status"),
            resultSet.getString("conclusion"),
            resultSet.getInt("attempt"),
            resultSet.getString("run_token"),
            resultSet.getTimestamp("lease_until") == null
                ? null
                : resultSet.getTimestamp("lease_until").toInstant(),
            resultSet.getInt("total_count"),
            resultSet.getInt("passed_count"),
            resultSet.getInt("failed_count"),
            resultSet.getInt("skipped_count")
        );
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Assay result cannot be serialized", exception);
        }
    }

    record ExecutionRecord(
        long id,
        long repositoryId,
        long mergeRequestId,
        String headCommit,
        long checkRunId,
        String status,
        String conclusion,
        int attempt,
        String runToken,
        Instant leaseUntil,
        int totalCount,
        int passedCount,
        int failedCount,
        int skippedCount
    ) {
        boolean terminal() {
            return !"PENDING".equals(status) && !"RUNNING".equals(status);
        }
    }

    record CaseResultRecord(
        long id,
        String caseKey,
        String sourcePath,
        String status,
        String failureCode,
        long durationMs,
        String assertionDiff
    ) {
    }
}
