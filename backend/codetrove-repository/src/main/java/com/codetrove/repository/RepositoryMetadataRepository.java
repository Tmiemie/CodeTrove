package com.codetrove.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class RepositoryMetadataRepository {

    private static final String SELECT_COLUMNS = """
        SELECT r.id, r.owner_id, u.username AS owner_username, r.name, r.slug, r.description,
               r.visibility, r.default_branch, r.storage_path, r.status, r.version,
               r.created_at, r.updated_at, member.role AS current_user_role
        FROM codetrove_repository r
        JOIN codetrove_user u ON u.id = r.owner_id
        LEFT JOIN codetrove_repository_member member
          ON member.repository_id = r.id AND member.user_id = ?
        """;

    private static final RowMapper<RepositoryRecord> REPOSITORY_ROW_MAPPER = (resultSet, rowNumber) -> {
        String role = resultSet.getString("current_user_role");
        return new RepositoryRecord(
            resultSet.getLong("id"),
            resultSet.getLong("owner_id"),
            resultSet.getString("owner_username"),
            resultSet.getString("name"),
            resultSet.getString("slug"),
            resultSet.getString("description"),
            RepositoryVisibility.valueOf(resultSet.getString("visibility")),
            resultSet.getString("default_branch"),
            resultSet.getString("storage_path"),
            RepositoryStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant(),
            role == null ? null : RepositoryRole.valueOf(role)
        );
    };

    private final JdbcTemplate jdbcTemplate;

    RepositoryMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void create(
        long id,
        long ownerId,
        String name,
        String slug,
        String description,
        RepositoryVisibility visibility,
        String defaultBranch,
        String storagePath
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_repository
                (id, owner_id, name, slug, description, visibility, default_branch,
                 storage_path, status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """,
            id,
            ownerId,
            name,
            slug,
            description,
            visibility.name(),
            defaultBranch,
            storagePath,
            RepositoryStatus.INITIALIZING.name(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_repository_member
                (repository_id, user_id, role, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            id,
            ownerId,
            RepositoryRole.OWNER.name(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    void activate(long repositoryId) {
        jdbcTemplate.update(
            "UPDATE codetrove_repository SET status = ?, version = version + 1 WHERE id = ?",
            RepositoryStatus.ACTIVE.name(),
            repositoryId
        );
    }

    Optional<RepositoryRecord> findVisibleById(long repositoryId, long currentUserId) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + """
            WHERE r.id = ?
              AND r.status = 'ACTIVE'
              AND (r.visibility = 'PUBLIC' OR member.user_id IS NOT NULL)
            """,
            REPOSITORY_ROW_MAPPER,
            currentUserId,
            repositoryId
        ).stream().findFirst();
    }

    Optional<RepositoryRecord> findActiveById(long repositoryId) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + " WHERE r.id = ? AND r.status = 'ACTIVE'",
            REPOSITORY_ROW_MAPPER,
            -1L,
            repositoryId
        ).stream().findFirst();
    }

    List<RepositoryRecord> findVisible(long currentUserId, Long beforeId, int limit) {
        if (beforeId == null) {
            return jdbcTemplate.query(
                SELECT_COLUMNS + """
                WHERE r.status = 'ACTIVE'
                  AND (r.visibility = 'PUBLIC' OR member.user_id IS NOT NULL)
                ORDER BY r.id DESC
                LIMIT ?
                """,
                REPOSITORY_ROW_MAPPER,
                currentUserId,
                limit
            );
        }
        return jdbcTemplate.query(
            SELECT_COLUMNS + """
            WHERE r.status = 'ACTIVE'
              AND r.id < ?
              AND (r.visibility = 'PUBLIC' OR member.user_id IS NOT NULL)
            ORDER BY r.id DESC
            LIMIT ?
            """,
            REPOSITORY_ROW_MAPPER,
            currentUserId,
            beforeId,
            limit
        );
    }

    Optional<RepositoryRecord> findActiveByOwnerAndSlug(
        String ownerUsername,
        String slug,
        long currentUserId
    ) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + """
            WHERE u.username = ?
              AND r.slug = ?
              AND r.status = 'ACTIVE'
            """,
            REPOSITORY_ROW_MAPPER,
            currentUserId,
            ownerUsername,
            slug
        ).stream().findFirst();
    }

    Optional<RepositoryRole> findRole(long repositoryId, long userId) {
        return jdbcTemplate.query(
            """
            SELECT role
            FROM codetrove_repository_member
            WHERE repository_id = ? AND user_id = ?
            """,
            (resultSet, rowNumber) -> RepositoryRole.valueOf(resultSet.getString("role")),
            repositoryId,
            userId
        ).stream().findFirst();
    }
}
