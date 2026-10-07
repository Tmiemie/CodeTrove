package com.codetrove.mergerequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class MergeRequestCommentRepository {

    private static final String SELECT_COLUMNS = """
        SELECT comment.id, comment.merge_request_id, comment.type, comment.file_path,
               comment.side, comment.line_number, comment.commit_id, comment.author_id,
               author.username AS author_username, author.display_name AS author_display_name,
               comment.author_type, comment.body, comment.created_at, comment.updated_at
        FROM codetrove_merge_request_comment comment
        LEFT JOIN codetrove_user author ON author.id = comment.author_id
        """;

    private static final RowMapper<MergeRequestCommentRecord> ROW_MAPPER = (resultSet, rowNumber) -> {
        String side = resultSet.getString("side");
        return new MergeRequestCommentRecord(
            resultSet.getLong("id"),
            resultSet.getLong("merge_request_id"),
            MergeRequestCommentType.valueOf(resultSet.getString("type")),
            resultSet.getString("file_path"),
            side == null ? null : DiffSide.valueOf(side),
            resultSet.getObject("line_number", Integer.class),
            resultSet.getString("commit_id"),
            resultSet.getObject("author_id", Long.class),
            resultSet.getString("author_username"),
            resultSet.getString("author_display_name"),
            resultSet.getString("author_type"),
            resultSet.getString("body"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant()
        );
    };

    private final JdbcTemplate jdbcTemplate;

    MergeRequestCommentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void create(
        long id,
        long mergeRequestId,
        MergeRequestCommentType type,
        String filePath,
        DiffSide side,
        Integer lineNumber,
        String commitId,
        long authorId,
        String body
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_merge_request_comment
                (id, merge_request_id, type, file_path, side, line_number, commit_id,
                 author_id, author_type, body, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?)
            """,
            id,
            mergeRequestId,
            type.name(),
            filePath,
            side == null ? null : side.name(),
            lineNumber,
            commitId,
            authorId,
            body,
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    boolean createSystemReview(
        long id,
        long mergeRequestId,
        String filePath,
        DiffSide side,
        int lineNumber,
        String commitId,
        String body,
        String fingerprint
    ) {
        Instant now = Instant.now();
        try {
            return jdbcTemplate.update(
                """
                INSERT INTO codetrove_merge_request_comment
                    (id, merge_request_id, type, file_path, side, line_number, commit_id,
                     author_id, author_type, body, fingerprint, created_at, updated_at)
                VALUES (?, ?, 'AI_REVIEW', ?, ?, ?, ?, NULL, 'SYSTEM', ?, ?, ?, ?)
                """,
                id,
                mergeRequestId,
                filePath,
                side.name(),
                lineNumber,
                commitId,
                body,
                fingerprint,
                Timestamp.from(now),
                Timestamp.from(now)
            ) == 1;
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            return false;
        }
    }

    boolean createSystemTestReport(
        long id,
        long mergeRequestId,
        String body,
        String fingerprint
    ) {
        Instant now = Instant.now();
        try {
            return jdbcTemplate.update(
                """
                INSERT INTO codetrove_merge_request_comment
                    (id, merge_request_id, type, file_path, side, line_number, commit_id,
                     author_id, author_type, body, fingerprint, created_at, updated_at)
                VALUES (?, ?, 'TEST_REPORT', NULL, NULL, NULL, NULL,
                        NULL, 'SYSTEM', ?, ?, ?, ?)
                """,
                id,
                mergeRequestId,
                body,
                fingerprint,
                Timestamp.from(now),
                Timestamp.from(now)
            ) == 1;
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            return false;
        }
    }

    Optional<MergeRequestCommentRecord> findById(long id) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + " WHERE comment.id = ?",
            ROW_MAPPER,
            id
        ).stream().findFirst();
    }

    List<MergeRequestCommentRecord> findPage(long mergeRequestId, Long afterId, int limit) {
        if (afterId == null) {
            return jdbcTemplate.query(
                SELECT_COLUMNS + """
                WHERE comment.merge_request_id = ?
                ORDER BY comment.id ASC
                LIMIT ?
                """,
                ROW_MAPPER,
                mergeRequestId,
                limit
            );
        }
        return jdbcTemplate.query(
            SELECT_COLUMNS + """
            WHERE comment.merge_request_id = ? AND comment.id > ?
            ORDER BY comment.id ASC
            LIMIT ?
            """,
            ROW_MAPPER,
            mergeRequestId,
            afterId,
            limit
        );
    }
}
