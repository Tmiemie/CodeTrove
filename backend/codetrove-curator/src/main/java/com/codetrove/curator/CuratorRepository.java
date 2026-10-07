package com.codetrove.curator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.codetrove.common.id.SnowflakeIdGenerator;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CuratorRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SnowflakeIdGenerator idGenerator;

    CuratorRepository(JdbcTemplate jdbcTemplate, SnowflakeIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
    }

    boolean markConsumed(String eventId, String eventType) {
        try {
            return jdbcTemplate.update(
                """
                INSERT INTO codetrove_consumed_event
                    (id, consumer_name, event_id, event_type, consumed_at)
                VALUES (?, 'codetrove-curator-v1', ?, ?, ?)
                """,
                idGenerator.nextId(),
                eventId,
                eventType,
                Timestamp.from(Instant.now())
            ) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    Optional<ReviewTaskRecord> findTask(long mergeRequestId, String headCommit) {
        return jdbcTemplate.query(
            """
            SELECT id, repository_id, merge_request_id, head_commit, check_run_id,
                   status, conclusion, attempt, finding_count
            FROM codetrove_review_task
            WHERE merge_request_id = ? AND head_commit = ?
            """,
            (resultSet, rowNumber) -> new ReviewTaskRecord(
                resultSet.getLong("id"),
                resultSet.getLong("repository_id"),
                resultSet.getLong("merge_request_id"),
                resultSet.getString("head_commit"),
                resultSet.getLong("check_run_id"),
                resultSet.getString("status"),
                resultSet.getString("conclusion"),
                resultSet.getInt("attempt"),
                resultSet.getInt("finding_count")
            ),
            mergeRequestId,
            headCommit
        ).stream().findFirst();
    }

    Optional<ReviewTaskRecord> findCurrentTask(long repositoryId, long mergeRequestId) {
        return jdbcTemplate.query(
            """
            SELECT task.id, task.repository_id, task.merge_request_id, task.head_commit,
                   task.check_run_id, task.status, task.conclusion, task.attempt, task.finding_count
            FROM codetrove_review_task task
            JOIN codetrove_merge_request mr ON mr.id = task.merge_request_id
            WHERE task.repository_id = ? AND task.merge_request_id = ?
              AND task.head_commit = mr.head_commit
            """,
            (resultSet, rowNumber) -> mapTask(resultSet),
            repositoryId,
            mergeRequestId
        ).stream().findFirst();
    }

    private ReviewTaskRecord mapTask(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ReviewTaskRecord(
            resultSet.getLong("id"),
            resultSet.getLong("repository_id"),
            resultSet.getLong("merge_request_id"),
            resultSet.getString("head_commit"),
            resultSet.getLong("check_run_id"),
            resultSet.getString("status"),
            resultSet.getString("conclusion"),
            resultSet.getInt("attempt"),
            resultSet.getInt("finding_count")
        );
    }

    long createRunningTask(
        long repositoryId,
        long mergeRequestId,
        String headCommit,
        long checkRunId,
        int attempt
    ) {
        long taskId = idGenerator.nextId();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_review_task
                (id, repository_id, merge_request_id, head_commit, check_run_id,
                 status, conclusion, attempt, finding_count, started_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'RUNNING', NULL, ?, 0, ?, ?, ?)
            """,
            taskId,
            repositoryId,
            mergeRequestId,
            headCommit,
            checkRunId,
            attempt,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return taskId;
    }

    List<Long> saveFindings(long taskId, List<ReviewFinding> findings) {
        Instant now = Instant.now();
        return findings.stream().map(finding -> {
            long findingId = idGenerator.nextId();
            jdbcTemplate.update(
                """
                INSERT INTO codetrove_review_finding
                    (id, review_task_id, skill, severity, rule_id, file_path, side,
                     line_number, title, message, evidence, suggestion, fingerprint,
                     disposition, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """,
                findingId,
                taskId,
                finding.skill(),
                finding.severity(),
                finding.ruleId(),
                finding.filePath(),
                finding.lineNumber(),
                finding.title(),
                finding.message(),
                finding.evidence(),
                finding.suggestion(),
                finding.fingerprint(),
                Timestamp.from(now),
                Timestamp.from(now)
            );
            return findingId;
        }).toList();
    }

    void completeTask(long taskId, String status, String conclusion, int findingCount) {
        jdbcTemplate.update(
            """
            UPDATE codetrove_review_task
            SET status = ?, conclusion = ?, finding_count = ?, finished_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ? AND status = 'RUNNING'
            """,
            status,
            conclusion,
            findingCount,
            taskId
        );
    }

    List<FindingRecord> findCurrentFindings(long repositoryId, long mergeRequestId) {
        return jdbcTemplate.query(
            """
            SELECT finding.id, finding.skill, finding.severity, finding.rule_id,
                   finding.file_path, finding.side, finding.line_number, finding.title,
                   finding.message, finding.evidence, finding.suggestion, finding.fingerprint,
                   finding.disposition
            FROM codetrove_review_finding finding
            JOIN codetrove_review_task task ON task.id = finding.review_task_id
            JOIN codetrove_merge_request mr ON mr.id = task.merge_request_id
            WHERE task.repository_id = ? AND task.merge_request_id = ?
              AND task.head_commit = mr.head_commit
            ORDER BY finding.id
            """,
            (resultSet, rowNumber) -> new FindingRecord(
                resultSet.getLong("id"),
                resultSet.getString("skill"),
                resultSet.getString("severity"),
                resultSet.getString("rule_id"),
                resultSet.getString("file_path"),
                resultSet.getString("side"),
                resultSet.getInt("line_number"),
                resultSet.getString("title"),
                resultSet.getString("message"),
                resultSet.getString("evidence"),
                resultSet.getString("suggestion"),
                resultSet.getString("fingerprint"),
                resultSet.getString("disposition")
            ),
            repositoryId,
            mergeRequestId
        );
    }

    record ReviewTaskRecord(
        long id,
        long repositoryId,
        long mergeRequestId,
        String headCommit,
        long checkRunId,
        String status,
        String conclusion,
        int attempt,
        int findingCount
    ) {
    }

    record FindingRecord(
        long id,
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
        String fingerprint,
        String disposition
    ) {
    }
}
