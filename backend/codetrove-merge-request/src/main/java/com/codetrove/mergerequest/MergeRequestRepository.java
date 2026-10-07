package com.codetrove.mergerequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class MergeRequestRepository {

    private static final String SELECT_COLUMNS = """
        SELECT mr.id, mr.repository_id, mr.iid, mr.title, mr.description,
               mr.source_branch, mr.target_branch, mr.base_commit, mr.head_commit,
               mr.status, mr.author_id, author.username AS author_username,
               author.display_name AS author_display_name, mr.merged_by, mr.merged_at,
               mr.merge_commit, mr.version, mr.created_at, mr.updated_at
        FROM codetrove_merge_request mr
        JOIN codetrove_user author ON author.id = mr.author_id
        """;

    private static final RowMapper<MergeRequestRecord> ROW_MAPPER = (resultSet, rowNumber) -> {
        Timestamp mergedAt = resultSet.getTimestamp("merged_at");
        Long mergedBy = resultSet.getObject("merged_by", Long.class);
        return new MergeRequestRecord(
            resultSet.getLong("id"),
            resultSet.getLong("repository_id"),
            resultSet.getInt("iid"),
            resultSet.getString("title"),
            resultSet.getString("description"),
            resultSet.getString("source_branch"),
            resultSet.getString("target_branch"),
            resultSet.getString("base_commit"),
            resultSet.getString("head_commit"),
            MergeRequestStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("author_id"),
            resultSet.getString("author_username"),
            resultSet.getString("author_display_name"),
            mergedBy,
            mergedAt == null ? null : mergedAt.toInstant(),
            resultSet.getString("merge_commit"),
            resultSet.getLong("version"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant()
        );
    };

    private final JdbcTemplate jdbcTemplate;

    MergeRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void lockRepository(long repositoryId) {
        jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_repository WHERE id = ? FOR UPDATE",
            Long.class,
            repositoryId
        );
    }

    int nextIid(long repositoryId) {
        Integer next = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(iid), 0) + 1 FROM codetrove_merge_request WHERE repository_id = ?",
            Integer.class,
            repositoryId
        );
        return next == null ? 1 : next;
    }

    boolean openExists(long repositoryId, String sourceBranch, String targetBranch) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM codetrove_merge_request
            WHERE repository_id = ? AND source_branch = ? AND target_branch = ? AND status = 'OPEN'
            """,
            Integer.class,
            repositoryId,
            sourceBranch,
            targetBranch
        );
        return count != null && count > 0;
    }

    void create(
        long id,
        long repositoryId,
        int iid,
        String title,
        String description,
        String sourceBranch,
        String targetBranch,
        String baseCommit,
        String headCommit,
        long authorId
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_merge_request
                (id, repository_id, iid, title, description, source_branch, target_branch,
                 base_commit, head_commit, status, author_id, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, 0, ?, ?)
            """,
            id,
            repositoryId,
            iid,
            title,
            description,
            sourceBranch,
            targetBranch,
            baseCommit,
            headCommit,
            authorId,
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    Optional<MergeRequestRecord> findById(long mergeRequestId) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + " WHERE mr.id = ?",
            ROW_MAPPER,
            mergeRequestId
        ).stream().findFirst();
    }

    Optional<MergeRequestRecord> findByIid(long repositoryId, int iid) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + " WHERE mr.repository_id = ? AND mr.iid = ?",
            ROW_MAPPER,
            repositoryId,
            iid
        ).stream().findFirst();
    }

    List<MergeRequestRecord> findPage(
        long repositoryId,
        MergeRequestStatus status,
        String targetBranch,
        Long beforeId,
        int limit
    ) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
            .append(" WHERE mr.repository_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(repositoryId);
        if (status != null) {
            sql.append(" AND mr.status = ?");
            parameters.add(status.name());
        }
        if (targetBranch != null) {
            sql.append(" AND mr.target_branch = ?");
            parameters.add(targetBranch);
        }
        if (beforeId != null) {
            sql.append(" AND mr.id < ?");
            parameters.add(beforeId);
        }
        sql.append(" ORDER BY mr.id DESC LIMIT ?");
        parameters.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, parameters.toArray());
    }

    boolean updateOpen(
        long repositoryId,
        int iid,
        String title,
        String description,
        MergeRequestStatus status,
        long version
    ) {
        return jdbcTemplate.update(
            """
            UPDATE codetrove_merge_request
            SET title = ?, description = ?, status = ?, version = version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE repository_id = ? AND iid = ? AND status = 'OPEN' AND version = ?
            """,
            title,
            description,
            status.name(),
            repositoryId,
            iid,
            version
        ) == 1;
    }
}
