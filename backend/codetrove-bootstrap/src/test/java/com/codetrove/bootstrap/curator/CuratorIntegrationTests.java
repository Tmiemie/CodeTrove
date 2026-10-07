package com.codetrove.bootstrap.curator;

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
import java.util.Map;
import java.util.UUID;

import com.codetrove.check.CheckEventProcessor;
import com.codetrove.curator.CuratorEventProcessor;
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
    "codetrove.curator.enabled=true",
    "codetrove.eventing.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CuratorIntegrationTests {

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
    private CuratorEventProcessor curatorProcessor;

    @BeforeEach
    void cleanState() throws IOException {
        cleanDatabaseAndStorage();
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        cleanDatabaseAndStorage();
    }

    @Test
    void realDiffCreatesFindingsCommentsAndSuccessfulCuratorCheck() throws Exception {
        Fixture fixture = createFixture("curator-success", vulnerableSource());
        createMergeRequest(fixture);
        checkProcessor.process(outboxPayload("mr.created"));
        String command = outboxPayload("curator.review-requested");

        curatorProcessor.process(command);
        curatorProcessor.process(command);
        checkProcessor.process(outboxPayload("curator.review-started"));
        checkProcessor.process(outboxPayload("curator.review-completed"));

        assertThat(count("codetrove_review_task")).isEqualTo(1);
        assertThat(count("codetrove_review_finding")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE type = 'AI_REVIEW'",
            Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_review_finding WHERE evidence LIKE '%secret-value-123%'",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_check_run WHERE name = 'curator.review'",
            String.class
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_consumed_event WHERE consumer_name = 'codetrove-curator-v1'",
            Integer.class
        )).isEqualTo(1);

        mockMvc.perform(get(reviewEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.task.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.task.findingCount").value(2))
            .andExpect(jsonPath("$.data.findings.length()").value(2));
        mockMvc.perform(get(reviewEndpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .param("skill", "SECURITY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.findings.length()").value(1))
            .andExpect(jsonPath("$.data.findings[0].ruleId").value("SEC001_HARDCODED_CREDENTIAL"));
    }

    @Test
    void duplicateReviewCommandDoesNotDuplicateTaskFindingOrComment() throws Exception {
        Fixture fixture = createFixture("curator-duplicate", vulnerableSource());
        createMergeRequest(fixture);
        checkProcessor.process(outboxPayload("mr.created"));
        String command = outboxPayload("curator.review-requested");

        curatorProcessor.process(command);
        curatorProcessor.process(command);

        assertThat(count("codetrove_review_task")).isEqualTo(1);
        assertThat(count("codetrove_review_finding")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE type = 'AI_REVIEW'",
            Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_outbox_event WHERE event_type = 'curator.review-completed'",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void staleHeadCommandCancelsReviewWithoutFindingOrComment() throws Exception {
        Fixture fixture = createFixture("curator-stale", vulnerableSource());
        createMergeRequest(fixture);
        checkProcessor.process(outboxPayload("mr.created"));
        String oldCommand = outboxPayload("curator.review-requested");
        String newHead = "c".repeat(40);
        long mergeRequestId = mergeRequestId(fixture.repositoryId());
        jdbcTemplate.update(
            "UPDATE codetrove_merge_request SET head_commit = ?, version = version + 1 WHERE id = ?",
            newHead,
            mergeRequestId
        );
        checkProcessor.process(headUpdatedEvent(fixture, mergeRequestId, newHead));

        curatorProcessor.process(oldCommand);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_review_task WHERE head_commit = ?",
            String.class,
            fixture.featureCommit()
        )).isEqualTo("CANCELLED");
        assertThat(count("codetrove_review_finding")).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request_comment WHERE type = 'AI_REVIEW'",
            Integer.class
        )).isZero();
    }

    private Fixture createFixture(String slug, String source) throws Exception {
        Session owner = registerAndLogin("owner-" + slug);
        String repositoryId = createRepository(owner, slug);
        String featureCommit;
        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(repositoryId)).toFile())
            .build()) {
            ObjectId main = repository.resolve("refs/heads/main");
            ObjectId feature = createCommit(repository, main, "src/Feature.java", source);
            updateRef(repository, "refs/heads/feature/curator", feature);
            featureCommit = feature.name();
        }
        return new Fixture(repositoryId, owner, featureCommit);
    }

    private void createMergeRequest(Fixture fixture) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/" + fixture.repositoryId() + "/merge-requests")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "title", "Curator integration MR",
                    "sourceBranch", "feature/curator",
                    "targetBranch", "main"
                ))))
            .andExpect(status().isCreated());
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

    private String vulnerableSource() {
        return """
            class Feature {
                String apiKey = "secret-value-123";
                void run(boolean ready) {
                    if (ready);
                    System.out.println("run");
                }
            }
            """;
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

    private String reviewEndpoint(String repositoryId) {
        return "/api/v1/repositories/" + repositoryId + "/merge-requests/1/review-findings";
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
                "CodeTrove Curator Test",
                "curator@codetrove.local",
                Instant.parse("2026-10-05T04:00:00Z"),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            commit.setParentId(parent);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("test: curator fixture");
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
                    "description", "Curator integration test",
                    "visibility", "PRIVATE",
                    "initializeWithReadme", true
                ))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asText();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void cleanDatabaseAndStorage() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_consumed_event");
        jdbcTemplate.update("DELETE FROM codetrove_outbox_event");
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
