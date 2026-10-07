package com.codetrove.bootstrap.assay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.codetrove.assay.AssayEventProcessor;
import com.codetrove.check.CheckEventProcessor;
import com.codetrove.eventing.DomainEvent;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "codetrove.curator.enabled=false",
    "codetrove.assay.enabled=true",
    "codetrove.eventing.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssayIntegrationTests {

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
    private CheckEventProcessor checkProcessor;

    @Autowired
    private AssayEventProcessor assayProcessor;

    @BeforeEach
    void cleanState() throws IOException {
        cleanDatabaseAndStorage();
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        cleanDatabaseAndStorage();
    }

    @Test
    void currentHeadCaseCompletesBlockingCheckAndWritesIdempotentReport() throws Exception {
        Fixture fixture = createFixture("assay-success", passingCase());
        createMergeRequest(fixture);
        checkProcessor.process(outboxPayload("mr.created"));
        String command = outboxPayload("assay.execution-requested");

        assayProcessor.process(command);
        assayProcessor.process(command);
        checkProcessor.process(outboxPayload("assay.execution-started"));
        checkProcessor.process(outboxPayload("assay.execution-completed"));

        assertThat(count("codetrove_assay_execution")).isEqualTo(1);
        assertThat(count("codetrove_assay_case_result")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_assay_execution",
            String.class
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_check_run WHERE name = 'assay.integration'",
            String.class
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE type = 'TEST_REPORT'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_outbox_event WHERE event_type = 'assay.execution-completed'",
            Integer.class
        )).isEqualTo(1);

        mockMvc.perform(get(reportEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.execution.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.execution.totalCount").value(1))
            .andExpect(jsonPath("$.data.cases[0].caseKey").value("assay.integration.success"))
            .andExpect(jsonPath("$.data.cases[0].assertionDiff.length()").value(0));
    }

    @Test
    void assertionFailurePersistsStructuredDiffAndKeepsGateClosed() throws Exception {
        Fixture fixture = createFixture("assay-failure", failingCase());
        createMergeRequest(fixture);
        checkProcessor.process(outboxPayload("mr.created"));

        assayProcessor.process(outboxPayload("assay.execution-requested"));
        checkProcessor.process(outboxPayload("assay.execution-started"));
        checkProcessor.process(outboxPayload("assay.execution-completed"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_assay_execution",
            String.class
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_check_run WHERE name = 'assay.integration'",
            String.class
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT assertion_diff FROM codetrove_assay_case_result",
            String.class
        )).contains("$.body.status", "SUCCESS", "FAILED");

        mockMvc.perform(get(reportEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.execution.status").value("FAILED"))
            .andExpect(jsonPath("$.data.cases[0].failureCode").value("ASSERTION_MISMATCH"))
            .andExpect(jsonPath("$.data.cases[0].assertionDiff[0].path")
                .value("$.body.status"))
            .andExpect(jsonPath("$.data.cases[0].assertionDiff[0].expected")
                .value("\"SUCCESS\""))
            .andExpect(jsonPath("$.data.cases[0].assertionDiff[0].actual")
                .value("\"FAILED\""));
    }

    @Test
    void staleHeadCommandCancelsWithoutCaseResultOrReportComment() throws Exception {
        Fixture fixture = createFixture("assay-stale", passingCase());
        createMergeRequest(fixture);
        checkProcessor.process(outboxPayload("mr.created"));
        String oldCommand = outboxPayload("assay.execution-requested");
        long mergeRequestId = mergeRequestId(fixture.repositoryId());
        String newHead = "d".repeat(40);
        jdbcTemplate.update(
            "UPDATE codetrove_merge_request SET head_commit = ?, version = version + 1 WHERE id = ?",
            newHead,
            mergeRequestId
        );
        checkProcessor.process(headUpdatedEvent(fixture, mergeRequestId, newHead));

        assayProcessor.process(oldCommand);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_assay_execution WHERE head_commit = ?",
            String.class,
            fixture.featureCommit()
        )).isEqualTo("CANCELLED");
        assertThat(count("codetrove_assay_case_result")).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE type = 'TEST_REPORT'",
            Integer.class
        )).isZero();
    }

    private Fixture createFixture(String slug, String testCase) throws Exception {
        Session owner = registerAndLogin("owner-" + slug);
        String repositoryId = createRepository(owner, slug);
        String featureCommit;
        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(repositoryId)).toFile())
            .build()) {
            ObjectId main = repository.resolve("refs/heads/main");
            Map<String, String> files = new LinkedHashMap<>();
            files.put("README.md", "# Assay fixture\n");
            files.put("testcases/integration.json", testCase);
            ObjectId feature = createCommit(repository, main, files);
            updateRef(repository, "refs/heads/feature/assay", feature);
            featureCommit = feature.name();
        }
        return new Fixture(repositoryId, owner, featureCommit);
    }

    private void createMergeRequest(Fixture fixture) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/" + fixture.repositoryId() + "/merge-requests")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "title", "Assay integration MR",
                    "sourceBranch", "feature/assay",
                    "targetBranch", "main"
                ))))
            .andExpect(status().isCreated());
    }

    private String passingCase() {
        return """
            {
              "schema_version":"1.0",
              "case_key":"assay.integration.success",
              "description":"passing integration case",
              "mocks":[{
                "id":"status-mock",
                "type":"http",
                "match":{"method":"GET","path":"/status"},
                "respond":{"status":200,"body":{"status":"SUCCESS"}},
                "expect_calls":{"min":1,"max":1}
              }],
              "request":{"target":"mock","method":"GET","path":"/status"},
              "assertions":[
                {"type":"response","operator":"equals","path":"$.status","expected":200},
                {"type":"response","operator":"equals","path":"$.body.status","expected":"SUCCESS"}
              ]
            }
            """;
    }

    private String failingCase() {
        return """
            {
              "schema_version":"1.0",
              "case_key":"assay.integration.failure",
              "description":"failing integration case",
              "mocks":[{
                "id":"status-mock",
                "type":"http",
                "match":{"method":"GET","path":"/status"},
                "respond":{"status":200,"body":{"status":"FAILED"}},
                "expect_calls":{"min":1,"max":1}
              }],
              "request":{"target":"mock","method":"GET","path":"/status"},
              "assertions":[
                {"type":"response","operator":"equals","path":"$.body.status","expected":"SUCCESS"}
              ]
            }
            """;
    }

    private String headUpdatedEvent(Fixture fixture, long mergeRequestId, String newHead)
        throws Exception {
        DomainEvent event = new DomainEvent(
            UUID.randomUUID().toString(),
            "mr.head-updated",
            1,
            Instant.now(),
            "codetrove-test",
            "test-trace",
            new DomainEvent.Aggregate("MERGE_REQUEST", Long.toString(mergeRequestId), 1),
            Map.of(
                "repository_id", fixture.repositoryId(),
                "mr_id", Long.toString(mergeRequestId),
                "mr_iid", 1,
                "base_commit", mainCommit(fixture.repositoryId()),
                "previous_head_commit", fixture.featureCommit(),
                "head_commit", newHead,
                "change_sequence", 2
            )
        );
        return objectMapper.writeValueAsString(event);
    }

    private ObjectId createCommit(
        org.eclipse.jgit.lib.Repository repository,
        ObjectId parent,
        Map<String, String> files
    ) throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            for (Map.Entry<String, String> file : files.entrySet()) {
                ObjectId blob = inserter.insert(
                    Constants.OBJ_BLOB,
                    file.getValue().getBytes(StandardCharsets.UTF_8)
                );
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(blob);
                builder.add(entry);
            }
            builder.finish();
            ObjectId tree = cache.writeTree(inserter);
            PersonIdent identity = new PersonIdent(
                "CodeTrove Assay Test",
                "assay@codetrove.local",
                Instant.parse("2026-10-05T06:00:00Z"),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            commit.setParentId(parent);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("test: assay fixture");
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
        return new Session("Bearer " + token);
    }

    private String createRepository(Session owner, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Repository " + slug,
                    "slug", slug,
                    "description", "Assay integration test",
                    "visibility", "PRIVATE",
                    "initializeWithReadme", true
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asText();
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

    private String reportEndpoint(String repositoryId) {
        return "/api/v1/repositories/" + repositoryId + "/merge-requests/1/test-report";
    }

    private String repositoryStoragePath(String repositoryId) {
        return jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(repositoryId)
        );
    }

    private String mainCommit(String repositoryId) throws IOException {
        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(repositoryId)).toFile())
            .build()) {
            return repository.resolve("refs/heads/main").name();
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void cleanDatabaseAndStorage() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_consumed_event");
        jdbcTemplate.update("DELETE FROM codetrove_outbox_event");
        jdbcTemplate.update("DELETE FROM codetrove_assay_case_result");
        jdbcTemplate.update("DELETE FROM codetrove_assay_execution");
        jdbcTemplate.update("DELETE FROM codetrove_review_finding");
        jdbcTemplate.update("DELETE FROM codetrove_review_task");
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

    private record Session(String authorization) {
    }

    private record Fixture(String repositoryId, Session owner, String featureCommit) {
    }
}
