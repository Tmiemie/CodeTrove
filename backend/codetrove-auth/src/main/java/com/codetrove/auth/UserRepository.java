package com.codetrove.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    private static final RowMapper<UserAccount> USER_ROW_MAPPER = (resultSet, rowNumber) -> new UserAccount(
        resultSet.getLong("id"),
        resultSet.getString("username"),
        resultSet.getString("password_hash"),
        resultSet.getString("display_name"),
        UserStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<UserAccount> findByUsername(String username) {
        return jdbcTemplate.query(
            """
            SELECT id, username, password_hash, display_name, status, created_at, updated_at
            FROM codetrove_user
            WHERE username = ?
            """,
            USER_ROW_MAPPER,
            username
        ).stream().findFirst();
    }

    Optional<UserAccount> findById(long id) {
        return jdbcTemplate.query(
            """
            SELECT id, username, password_hash, display_name, status, created_at, updated_at
            FROM codetrove_user
            WHERE id = ?
            """,
            USER_ROW_MAPPER,
            id
        ).stream().findFirst();
    }

    UserAccount create(long id, String username, String passwordHash, String displayName) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
                """
                INSERT INTO codetrove_user
                    (id, username, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                username,
                passwordHash,
                displayName,
                UserStatus.ACTIVE.name(),
                Timestamp.from(now),
                Timestamp.from(now)
            );
        } catch (DuplicateKeyException exception) {
            throw exception;
        }
        return new UserAccount(id, username, passwordHash, displayName, UserStatus.ACTIVE, now, now);
    }
}
