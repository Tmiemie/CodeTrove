package com.codetrove.bootstrap.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import com.codetrove.check.CheckEventProcessor;
import com.codetrove.eventing.DomainEvent;
import com.codetrove.eventing.OutboxPublisher;
import com.codetrove.repository.RepositoryRefUpdate;
import com.codetrove.repository.RepositoryRefUpdateListener;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "codetrove.check-gate.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckGateApiTests {

    private static final Path STORAGE_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "codetrove-test-repositories"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CheckEventProcessor eventProcessor;

    @Autowired
    private java.util.List<RepositoryRefUpdateListener> refUpdateListeners;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanState() throws IOException {
        cleanDatabaseAndStorage();
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        cleanDatabaseAndStorage();
    }

    @Test
    void mrCreatedOutboxAndDuplicateConsumptionCreateOneSuite() throws Exception {
        Fixture fixture = createFixture("check-created");
        createMergeRequest(fixture);
        String payload = outboxPayload("mr.created");
        eventProcessor.process(payload);
        eventProcessor.process(payload);

        assertThat(count("codetrove_outbox_event")).isEqualTo(1);
        assertThat(count("codetrove_consumed_event")).isEqualTo(1);
        assertThat(count("codetrove_check_suite")).isEqualTo(1);
        assertThat(count("codetrove_check_run")).isEqualTo(2);

        mockMvc.perform(get(checksEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.current.headCommit").value(fixture.featureCommit()))
            .andExpect(jsonPath("$.data.current.status").value("PENDING"))
            .andExpect(jsonPath("$.data.current.runs.length()").value(2));
    }

    @Test
    void blockingAssayMustSucceedBeforeMerge() throws Exception {
        Fixture fixture = createFixture("check-gate");
        createMergeRequest(fixture);
        eventProcessor.process(outboxPayload("mr.created"));

        mockMvc.perform(post(mergeEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "check-gate-blocked")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_CHECKS_NOT_PASSED"));

        long mergeRequestId = mergeRequestId(fixture.repositoryId());
        long runId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_check_run WHERE name = 'assay.integration'",
            Long.class
        );
        eventProcessor.process(resultEvent(
            "assay.execution-completed",
            mergeRequestId,
            fixture.featureCommit(),
            runId,
            "ASSAY",
            "SUCCESS"
        ));
        jdbcTemplate.update(
            """
            UPDATE codetrove_check_run
            SET status = 'FAILED', conclusion = 'NON_BLOCKING_WARNING'
            WHERE name = 'curator.review'
            """
        );

        mockMvc.perform(post(mergeEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "check-gate-success")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("MERGED"));
    }

    @Test
    void newHeadMakesOldSuiteHistoricalAndStaleResultCannotUnlockIt() throws Exception {
        Fixture fixture = createFixture("check-stale");
        createMergeRequest(fixture);
        eventProcessor.process(outboxPayload("mr.created"));
        long oldRunId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_check_run WHERE name = 'assay.integration'",
            Long.class
        );
        String nextHead;
        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(fixture.repositoryId())).toFile())
            .build()) {
            ObjectId commit = createCommit(
                repository,
                ObjectId.fromString(fixture.featureCommit()),
                "src/Next.java",
                "class Next {}\n"
            );
            updateRef(repository, "refs/heads/feature/check", commit);
            nextHead = commit.name();
        }
        RepositoryRefUpdate refUpdate = new RepositoryRefUpdate(
            "refs/heads/feature/check",
            fixture.featureCommit(),
            nextHead,
            false
        );
        for (RepositoryRefUpdateListener listener : refUpdateListeners) {
            listener.afterRefsUpdated(Long.parseLong(fixture.repositoryId()), java.util.List.of(refUpdate));
        }
        eventProcessor.process(outboxPayload("mr.head-updated"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_check_suite WHERE is_current = TRUE",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_check_suite WHERE is_current = FALSE",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_check_run WHERE id = ?",
            String.class,
            oldRunId
        )).isEqualTo("CANCELLED");

        eventProcessor.process(resultEvent(
            "assay.execution-completed",
            mergeRequestId(fixture.repositoryId()),
            fixture.featureCommit(),
            oldRunId,
            "ASSAY",
            "SUCCESS"
        ));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_check_run WHERE id = ?",
            String.class,
            oldRunId
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM codetrove_check_run r
            JOIN codetrove_check_suite s ON s.id = r.check_suite_id
            WHERE s.is_current = TRUE AND r.blocking = TRUE AND r.status = 'PENDING'
            """,
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void runningAndFailedBlockingChecksRejectMerge() throws Exception {
        Fixture fixture = createFixture("check-non-success");
        createMergeRequest(fixture);
        eventProcessor.process(outboxPayload("mr.created"));
        long runId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_check_run WHERE name = 'assay.integration'",
            Long.class
        );
        jdbcTemplate.update(
            "UPDATE codetrove_check_run SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            runId
        );
        assertMergeBlocked(fixture, "check-running-blocked");

        jdbcTemplate.update("UPDATE codetrove_check_run SET status = 'PENDING' WHERE id = ?", runId);
        eventProcessor.process(resultEvent(
            "assay.execution-completed",
            mergeRequestId(fixture.repositoryId()),
            fixture.featureCommit(),
            runId,
            "ASSAY",
            "FAILED"
        ));
        assertMergeBlocked(fixture, "check-failed-blocked");
    }

    @Test
    void invalidResultRollsBackConsumedMarker() throws Exception {
        Fixture fixture = createFixture("invalid-event");
        createMergeRequest(fixture);
        eventProcessor.process(outboxPayload("mr.created"));
        long runId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_check_run WHERE name = 'assay.integration'",
            Long.class
        );
        int consumedBefore = count("codetrove_consumed_event");
        String invalid = resultEvent(
            "assay.execution-completed",
            mergeRequestId(fixture.repositoryId()),
            fixture.featureCommit(),
            runId,
            "CURATOR",
            "SUCCESS"
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> eventProcessor.process(invalid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("codetrove_consumed_event")).isEqualTo(consumedBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_check_run WHERE id = ?",
            String.class,
            runId
        )).isEqualTo("PENDING");
    }

    @Test
    void successfulOutboxPublishMarksEventPublished() throws Exception {
        Fixture fixture = createFixture("outbox-success");
        createMergeRequest(fixture);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        OutboxPublisher publisher = new OutboxPublisher(
            jdbcTemplate,
            kafkaTemplate,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            10,
            2,
            Duration.ofSeconds(1)
        );

        publisher.publishReady();
        assertThat(outboxState()).isEqualTo("PUBLISHED:1");
    }

    @Test
    void failedOutboxPublishRetriesThenMovesToFailed() throws Exception {
        Fixture fixture = createFixture("outbox-retry");
        createMergeRequest(fixture);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        OutboxPublisher publisher = new OutboxPublisher(
            jdbcTemplate,
            kafkaTemplate,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            10,
            2,
            Duration.ofSeconds(1)
        );

        publisher.publishReady();
        assertThat(outboxState()).isEqualTo("PENDING:1");
        jdbcTemplate.update(
            "UPDATE codetrove_outbox_event SET available_at = CURRENT_TIMESTAMP(6)"
        );
        publisher.publishReady();
        assertThat(outboxState()).isEqualTo("FAILED:2");
    }

    private void assertMergeBlocked(Fixture fixture, String idempotencyKey) throws Exception {
        mockMvc.perform(post(mergeEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_CHECKS_NOT_PASSED"));
    }

    private Fixture createFixture(String slug) throws Exception {
        Session owner = registerAndLogin("owner-" + slug);
        String repositoryId = createRepository(owner, slug);
        String featureCommit;
        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(repositoryId)).toFile())
            .build()) {
            ObjectId main = repository.resolve("refs/heads/main");
            ObjectId feature = createCommit(repository, main, "src/Feature.java", "class Feature {}\n");
            updateRef(repository, "refs/heads/feature/check", feature);
            featureCommit = feature.name();
        }
        return new Fixture(repositoryId, owner, featureCommit);
    }

    private void createMergeRequest(Fixture fixture) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/" + fixture.repositoryId() + "/merge-requests")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "title", "Check gate MR",
                    "sourceBranch", "feature/check",
                    "targetBranch", "main"
                ))))
            .andExpect(status().isCreated());
    }

    private String resultEvent(
        String eventType,
        long mergeRequestId,
        String headCommit,
        long runId,
        String checkType,
        String status
    ) throws Exception {
        DomainEvent event = new DomainEvent(
            UUID.randomUUID().toString(),
            eventType,
            1,
            Instant.now(),
            "codetrove-test",
            "test-trace",
            new DomainEvent.Aggregate("CHECK_RUN", Long.toString(runId), 1),
            Map.of(
                "mr_id", Long.toString(mergeRequestId),
                "head_commit", headCommit,
                "check_run_id", Long.toString(runId),
                "check_type", checkType,
                "status", status,
                "conclusion", "TEST_PASSED"
            )
        );
        return objectMapper.writeValueAsString(event);
    }

    private String outboxPayload(String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT payload FROM codetrove_outbox_event WHERE event_type = ? ORDER BY id DESC LIMIT 1",
            String.class,
            eventType
        );
    }

    private long mergeRequestId(String repositoryId) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_merge_request WHERE repository_id = ? AND iid = 1",
            Long.class,
            Long.parseLong(repositoryId)
        );
    }

    private String outboxState() {
        return jdbcTemplate.queryForObject(
            "SELECT CONCAT(status, ':', attempts) FROM codetrove_outbox_event",
            String.class
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String mergeEndpoint(String repositoryId) {
        return "/api/v1/repositories/" + repositoryId + "/merge-requests/1/merge";
    }

    private String checksEndpoint(String repositoryId) {
        return "/api/v1/repositories/" + repositoryId + "/merge-requests/1/checks";
    }

    private String repositoryStoragePath(String repositoryId) {
        return jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(repositoryId)
        );
    }

    private ObjectId createCommit(
        org.eclipse.jgit.lib.Repository repository,
        ObjectId parent,
        String filePath,
        String content
    ) throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            ObjectId readme = repository.resolve("main^{tree}:README.md");
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            DirCacheEntry readmeEntry = new DirCacheEntry("README.md");
            readmeEntry.setFileMode(FileMode.REGULAR_FILE);
            readmeEntry.setObjectId(readme);
            builder.add(readmeEntry);
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
            DirCacheEntry entry = new DirCacheEntry(filePath);
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(blob);
            builder.add(entry);
            builder.finish();
            ObjectId tree = cache.writeTree(inserter);
            PersonIdent identity = new PersonIdent(
                "CodeTrove Check Test",
                "check@codetrove.local",
                Instant.parse("2026-10-05T04:00:00Z"),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            commit.setParentId(parent);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("test: check gate fixture");
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return commitId;
        }
    }

    private void updateRef(
        org.eclipse.jgit.lib.Repository repository,
        String refName,
        ObjectId commitId
    ) throws IOException {
        RefUpdate update = repository.updateRef(refName);
        update.setNewObjectId(commitId);
        update.setForceUpdate(true);
        assertThat(update.update()).isIn(
            RefUpdate.Result.NEW,
            RefUpdate.Result.FAST_FORWARD,
            RefUpdate.Result.FORCED
        );
    }

    private Session registerAndLogin(String username) throws Exception {
        String password = "secure-password-for-" + username;
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "password", password,
                    "displayName", username
                ))))
            .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "password", password
                ))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).path("data").path("accessToken").asText();
        return new Session(username, "Bearer " + token);
    }

    private String createRepository(Session owner, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Repository " + slug,
                    "slug", slug,
                    "description", "Check gate integration test",
                    "visibility", "PRIVATE",
                    "initializeWithReadme", true
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asText();
    }

    private void cleanDatabaseAndStorage() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_consumed_event");
        jdbcTemplate.update("DELETE FROM codetrove_outbox_event");
        jdbcTemplate.update("DELETE FROM codetrove_check_run");
        jdbcTemplate.update("DELETE FROM codetrove_check_suite");
        jdbcTemplate.update("DELETE FROM codetrove_merge_request_comment");
        jdbcTemplate.update("DELETE FROM codetrove_merge_operation");
        jdbcTemplate.update("DELETE FROM codetrove_merge_request_commit");
        jdbcTemplate.update("DELETE FROM codetrove_merge_request");
        jdbcTemplate.update("DELETE FROM codetrove_repository_member");
        jdbcTemplate.update("DELETE FROM codetrove_repository");
        jdbcTemplate.update("DELETE FROM codetrove_user");
        new WindowCacheConfig().install();
        deleteRecursively(STORAGE_ROOT);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                current.toFile().setWritable(true);
                Files.deleteIfExists(current);
            }
        }
    }

    private record MergeCommand(String expectedHeadCommit, String strategy) {
    }

    private record Session(String username, String authorization) {
    }

    private record Fixture(String repositoryId, Session owner, String featureCommit) {
    }
}
