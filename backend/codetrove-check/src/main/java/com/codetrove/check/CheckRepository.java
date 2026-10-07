package com.codetrove.check;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.codetrove.common.id.SnowflakeIdGenerator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CheckRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final boolean curatorEnabled;
    private final boolean assayEnabled;

    CheckRepository(
        JdbcTemplate jdbcTemplate,
        SnowflakeIdGenerator idGenerator,
        @Value("${codetrove.curator.enabled:false}") boolean curatorEnabled,
        @Value("${codetrove.assay.enabled:false}") boolean assayEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
        this.curatorEnabled = curatorEnabled;
        this.assayEnabled = assayEnabled;
    }

    boolean markConsumed(String consumerName, String eventId, String eventType) {
        try {
            return jdbcTemplate.update(
                """
                INSERT INTO codetrove_consumed_event
                    (id, consumer_name, event_id, event_type, consumed_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                idGenerator.nextId(),
                consumerName,
                eventId,
                eventType,
                Timestamp.from(Instant.now())
            ) == 1;
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            return false;
        }
    }

    SuiteActivation activateSuite(long mergeRequestId, String headCommit) {
        jdbcTemplate.update(
            """
            UPDATE codetrove_check_suite
            SET is_current = FALSE, status = CASE
                    WHEN status IN ('PENDING', 'RUNNING') THEN 'CANCELLED'
                    ELSE status END,
                version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
            WHERE merge_request_id = ? AND is_current = TRUE AND head_commit <> ?
            """,
            mergeRequestId,
            headCommit
        );
        Optional<Long> existing = jdbcTemplate.query(
            "SELECT id FROM codetrove_check_suite WHERE merge_request_id = ? AND head_commit = ?",
            (resultSet, rowNumber) -> resultSet.getLong("id"),
            mergeRequestId,
            headCommit
        ).stream().findFirst();
        long suiteId;
        boolean created;
        if (existing.isPresent()) {
            suiteId = existing.get();
            created = false;
            jdbcTemplate.update(
                """
                UPDATE codetrove_check_suite
                SET is_current = TRUE, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """,
                suiteId
            );
        } else {
            suiteId = idGenerator.nextId();
            created = true;
            Instant now = Instant.now();
            jdbcTemplate.update(
                """
                INSERT INTO codetrove_check_suite
                    (id, merge_request_id, head_commit, status, is_current, version, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', TRUE, 0, ?, ?)
                """,
                suiteId,
                mergeRequestId,
                headCommit,
                Timestamp.from(now),
                Timestamp.from(now)
            );
            createRun(
                suiteId,
                "CURATOR",
                "curator.review",
                false,
                curatorEnabled ? "PENDING" : "SKIPPED",
                curatorEnabled ? null : "NOT_IMPLEMENTED"
            );
            createRun(
                suiteId,
                "ASSAY",
                "assay.integration",
                true,
                "PENDING",
                assayEnabled ? null : "NOT_IMPLEMENTED"
            );
        }
        cancelRunsInHistoricalSuites(mergeRequestId, suiteId);
        recomputeSuite(suiteId);
        long curatorRunId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_check_run WHERE check_suite_id = ? AND name = 'curator.review'",
            Long.class,
            suiteId
        );
        long assayRunId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_check_run WHERE check_suite_id = ? AND name = 'assay.integration'",
            Long.class,
            suiteId
        );
        return new SuiteActivation(
            suiteId,
            curatorRunId,
            assayRunId,
            created,
            curatorEnabled,
            assayEnabled
        );
    }

    Optional<CheckSuiteRecord> findCurrent(long mergeRequestId) {
        return findSuites(mergeRequestId, true).stream().findFirst();
    }

    List<CheckSuiteRecord> findSuites(long mergeRequestId, boolean currentOnly) {
        String filter = currentOnly ? " AND s.is_current = TRUE" : "";
        List<CheckSuiteRecord> suites = jdbcTemplate.query(
            """
            SELECT s.id, s.merge_request_id, s.head_commit, s.status, s.is_current,
                   s.version, s.created_at, s.updated_at
            FROM codetrove_check_suite s
            WHERE s.merge_request_id = ?
            """ + filter + " ORDER BY s.id DESC",
            (resultSet, rowNumber) -> new CheckSuiteRecord(
                resultSet.getLong("id"),
                resultSet.getLong("merge_request_id"),
                resultSet.getString("head_commit"),
                resultSet.getString("status"),
                resultSet.getBoolean("is_current"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                List.of()
            ),
            mergeRequestId
        );
        return suites.stream().map(suite -> suite.withRuns(findRuns(suite.id()))).toList();
    }

    boolean blockingChecksPassed(long mergeRequestId, String headCommit) {
        Integer suiteCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM codetrove_check_suite
            WHERE merge_request_id = ? AND head_commit = ? AND is_current = TRUE
            """,
            Integer.class,
            mergeRequestId,
            headCommit
        );
        if (suiteCount == null || suiteCount != 1) {
            return false;
        }
        Integer blockingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM codetrove_check_run r
            JOIN codetrove_check_suite s ON s.id = r.check_suite_id
            WHERE s.merge_request_id = ? AND s.head_commit = ? AND s.is_current = TRUE
              AND r.blocking = TRUE
            """,
            Integer.class,
            mergeRequestId,
            headCommit
        );
        Integer notPassed = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM codetrove_check_run r
            JOIN codetrove_check_suite s ON s.id = r.check_suite_id
            WHERE s.merge_request_id = ? AND s.head_commit = ? AND s.is_current = TRUE
              AND r.blocking = TRUE AND r.status <> 'SUCCESS'
            """,
            Integer.class,
            mergeRequestId,
            headCommit
        );
        return blockingCount != null && blockingCount > 0 && notPassed != null && notPassed == 0;
    }

    void startRun(long runId, long mergeRequestId, String headCommit, String checkType) {
        int updated = jdbcTemplate.update(
            """
            UPDATE codetrove_check_run
            SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ? AND check_type = ? AND check_suite_id IN (
                SELECT s.id FROM codetrove_check_suite s
                WHERE s.merge_request_id = ? AND s.head_commit = ? AND s.is_current = TRUE
            ) AND status = 'PENDING'
            """,
            runId,
            checkType,
            mergeRequestId,
            headCommit
        );
        if (updated == 1) {
            Long suiteId = jdbcTemplate.queryForObject(
                "SELECT check_suite_id FROM codetrove_check_run WHERE id = ?",
                Long.class,
                runId
            );
            if (suiteId != null) {
                recomputeSuite(suiteId);
            }
        }
    }

    void completeRun(
        long runId,
        long mergeRequestId,
        String headCommit,
        String checkType,
        String status,
        String conclusion
    ) {
        int updated = jdbcTemplate.update(
            """
            UPDATE codetrove_check_run
            SET status = ?, conclusion = ?, finished_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ? AND check_type = ? AND check_suite_id IN (
                SELECT s.id FROM codetrove_check_suite s
                WHERE s.merge_request_id = ? AND s.head_commit = ? AND s.is_current = TRUE
            ) AND status IN ('PENDING', 'RUNNING')
            """,
            status,
            conclusion,
            runId,
            checkType,
            mergeRequestId,
            headCommit
        );
        if (updated == 1) {
            Long suiteId = jdbcTemplate.queryForObject(
                "SELECT check_suite_id FROM codetrove_check_run WHERE id = ?",
                Long.class,
                runId
            );
            if (suiteId != null) {
                recomputeSuite(suiteId);
            }
        }
    }

    void deactivateSuites(long mergeRequestId, String conclusion) {
        jdbcTemplate.update(
            """
            UPDATE codetrove_check_run
            SET status = 'CANCELLED', conclusion = ?, finished_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE check_suite_id IN (
                SELECT id FROM codetrove_check_suite WHERE merge_request_id = ?
            ) AND status IN ('PENDING', 'RUNNING')
            """,
            conclusion,
            mergeRequestId
        );
        jdbcTemplate.update(
            """
            UPDATE codetrove_check_suite
            SET is_current = FALSE,
                status = CASE WHEN status IN ('PENDING', 'RUNNING') THEN 'CANCELLED' ELSE status END,
                version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
            WHERE merge_request_id = ? AND is_current = TRUE
            """,
            mergeRequestId
        );
    }

    private long createRun(
        long suiteId,
        String checkType,
        String name,
        boolean blocking,
        String status,
        String conclusion
    ) {
        Instant now = Instant.now();
        Timestamp finished = isTerminal(status) ? Timestamp.from(now) : null;
        long runId = idGenerator.nextId();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_check_run
                (id, check_suite_id, check_type, name, blocking, status, conclusion,
                 attempt, started_at, finished_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, NULL, ?, ?, ?)
            """,
            runId,
            suiteId,
            checkType,
            name,
            blocking,
            status,
            conclusion,
            finished,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return runId;
    }

    private List<CheckRunRecord> findRuns(long suiteId) {
        return jdbcTemplate.query(
            """
            SELECT id, check_suite_id, check_type, name, blocking, status, conclusion,
                   details_url, attempt, started_at, finished_at, created_at, updated_at
            FROM codetrove_check_run
            WHERE check_suite_id = ? ORDER BY name, attempt
            """,
            (resultSet, rowNumber) -> new CheckRunRecord(
                resultSet.getLong("id"),
                resultSet.getString("check_type"),
                resultSet.getString("name"),
                resultSet.getBoolean("blocking"),
                resultSet.getString("status"),
                resultSet.getString("conclusion"),
                resultSet.getString("details_url"),
                resultSet.getInt("attempt"),
                toInstant(resultSet.getTimestamp("started_at")),
                toInstant(resultSet.getTimestamp("finished_at")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
            ),
            suiteId
        );
    }

    private void cancelRunsInHistoricalSuites(long mergeRequestId, long currentSuiteId) {
        jdbcTemplate.update(
            """
            UPDATE codetrove_check_run r
            SET status = 'CANCELLED', conclusion = 'STALE_HEAD',
                finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
            WHERE r.check_suite_id IN (
                SELECT s.id FROM codetrove_check_suite s
                WHERE s.merge_request_id = ? AND s.id <> ?
            ) AND r.status IN ('PENDING', 'RUNNING')
            """,
            mergeRequestId,
            currentSuiteId
        );
    }

    private void recomputeSuite(long suiteId) {
        List<String> statuses = jdbcTemplate.queryForList(
            "SELECT status FROM codetrove_check_run WHERE check_suite_id = ?",
            String.class,
            suiteId
        );
        String aggregate;
        if (statuses.stream().anyMatch("FAILED"::equals)) {
            aggregate = "FAILED";
        } else if (statuses.stream().anyMatch(status -> "PENDING".equals(status) || "RUNNING".equals(status))) {
            aggregate = "PENDING";
        } else if (statuses.stream().allMatch(status -> "SKIPPED".equals(status) || "CANCELLED".equals(status))) {
            aggregate = "SKIPPED";
        } else {
            aggregate = "SUCCESS";
        }
        jdbcTemplate.update(
            """
            UPDATE codetrove_check_suite
            SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ?
            """,
            aggregate,
            suiteId
        );
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status)
            || "SKIPPED".equals(status) || "CANCELLED".equals(status);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record SuiteActivation(
        long suiteId,
        long curatorRunId,
        long assayRunId,
        boolean created,
        boolean curatorEnabled,
        boolean assayEnabled
    ) {
    }

    record CheckSuiteRecord(
        long id,
        long mergeRequestId,
        String headCommit,
        String status,
        boolean current,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<CheckRunRecord> runs
    ) {
        CheckSuiteRecord withRuns(List<CheckRunRecord> nextRuns) {
            return new CheckSuiteRecord(
                id,
                mergeRequestId,
                headCommit,
                status,
                current,
                version,
                createdAt,
                updatedAt,
                nextRuns
            );
        }
    }

    record CheckRunRecord(
        long id,
        String checkType,
        String name,
        boolean blocking,
        String status,
        String conclusion,
        String detailsUrl,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
