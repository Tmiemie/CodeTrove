package com.codetrove.mergerequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.codetrove.common.id.SnowflakeIdGenerator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MergeRequestHistoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SnowflakeIdGenerator idGenerator;

    MergeRequestHistoryRepository(JdbcTemplate jdbcTemplate, SnowflakeIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
    }

    void recordInitial(long mergeRequestId, String commitId) {
        insertHistory(mergeRequestId, commitId, 1);
    }

    List<Long> findOpenMergeRequestIds(long repositoryId, String sourceBranch, String newCommit) {
        return jdbcTemplate.queryForList(
            """
            SELECT id FROM codetrove_merge_request
            WHERE repository_id = ? AND source_branch = ? AND status = 'OPEN' AND head_commit <> ?
            ORDER BY id
            """,
            Long.class,
            repositoryId,
            sourceBranch,
            newCommit
        );
    }

    HeadUpdate updateHeadAndRecord(long mergeRequestId, String newCommit) {
        String previousHead = jdbcTemplate.queryForObject(
            "SELECT head_commit FROM codetrove_merge_request WHERE id = ?",
            String.class,
            mergeRequestId
        );
        int updated = jdbcTemplate.update(
            """
            UPDATE codetrove_merge_request
            SET head_commit = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ? AND status = 'OPEN' AND head_commit <> ?
            """,
            newCommit,
            mergeRequestId,
            newCommit
        );
        if (updated == 0) {
            return null;
        }
        Integer nextSequence = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(sequence_number), 0) + 1
            FROM codetrove_merge_request_commit
            WHERE merge_request_id = ?
            """,
            Integer.class,
            mergeRequestId
        );
        int sequence = nextSequence == null ? 1 : nextSequence;
        insertHistory(mergeRequestId, newCommit, sequence);
        return new HeadUpdate(previousHead, sequence);
    }

    record HeadUpdate(String previousHead, int sequence) {
    }

    private void insertHistory(long mergeRequestId, String commitId, int sequence) {
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_merge_request_commit
                (id, merge_request_id, commit_id, sequence_number, observed_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            idGenerator.nextId(),
            mergeRequestId,
            commitId,
            sequence,
            Timestamp.from(Instant.now())
        );
    }
}
