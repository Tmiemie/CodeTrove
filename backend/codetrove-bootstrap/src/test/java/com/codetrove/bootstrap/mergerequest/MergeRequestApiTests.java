package com.codetrove.bootstrap.mergerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.eclipse.jgit.revwalk.RevWalk;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergeRequestApiTests {

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
    private java.util.List<RepositoryRefUpdateListener> refUpdateListeners;

    @BeforeEach
    void cleanState() throws IOException {
        cleanDatabaseAndStorage();
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        cleanDatabaseAndStorage();
    }

    @Test
    void createsMergeRequestFromExactLocalBranchSnapshots() throws Exception {
        Fixture fixture = createFixture("mr-create");

        String body = createMergeRequest(fixture, "feature/one", "Add validation")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.iid").value(1))
            .andExpect(jsonPath("$.data.sourceBranch").value("feature/one"))
            .andExpect(jsonPath("$.data.targetBranch").value("main"))
            .andExpect(jsonPath("$.data.baseCommit").value(fixture.mainCommit()))
            .andExpect(jsonPath("$.data.headCommit").value(fixture.featureOneCommit()))
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.author.username").value(fixture.owner().username()))
            .andExpect(jsonPath("$.data.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String id = objectMapper.readTree(body).path("data").path("id").asText();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request WHERE id = ? AND base_commit = ? AND head_commit = ?",
            Integer.class,
            Long.parseLong(id),
            fixture.mainCommit(),
            fixture.featureOneCommit()
        )).isEqualTo(1);
    }

    @Test
    void rejectsIdenticalMissingAndDuplicateOpenBranches() throws Exception {
        Fixture fixture = createFixture("mr-invalid");

        mockMvc.perform(post(endpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequest(
                    "Same branch",
                    null,
                    "main",
                    "main"
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_BRANCHES_IDENTICAL"));

        mockMvc.perform(post(endpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequest(
                    "Missing branch",
                    null,
                    "feature/missing",
                    "main"
                ))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_REF_NOT_FOUND"));

        createMergeRequest(fixture, "feature/one", "First").andExpect(status().isCreated());
        createMergeRequest(fixture, "feature/one", "Duplicate")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_ALREADY_OPEN"));
    }

    @Test
    void listsAndFiltersMergeRequestsWithOpaqueCursor() throws Exception {
        Fixture fixture = createFixture("mr-list");
        createMergeRequest(fixture, "feature/one", "First").andExpect(status().isCreated());
        createMergeRequest(fixture, "feature/two", "Second").andExpect(status().isCreated());

        String firstPage = mockMvc.perform(get(endpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .param("status", "OPEN")
                .param("targetBranch", "main")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].iid").value(2))
            .andExpect(jsonPath("$.meta.nextCursor").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String cursor = objectMapper.readTree(firstPage).path("meta").path("nextCursor").asText();

        mockMvc.perform(get(endpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .param("limit", "1")
                .param("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].iid").value(1))
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist());

        mockMvc.perform(get(endpoint(fixture.repositoryId()))
                .header("Authorization", fixture.owner().authorization())
                .param("cursor", "invalid!"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void authorCanEditAndCloseWithOptimisticVersion() throws Exception {
        Fixture fixture = createFixture("mr-update");
        createMergeRequest(fixture, "feature/one", "Original").andExpect(status().isCreated());

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(
                    "Updated",
                    "new description",
                    null,
                    0L
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Updated"))
            .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(
                    "Stale",
                    null,
                    null,
                    0L
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(
                    null,
                    null,
                    "CLOSED",
                    1L
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(
                    "Cannot reopen",
                    null,
                    null,
                    2L
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_NOT_OPEN"));
    }

    @Test
    void nonAuthorMemberCannotEditButOwnerCanEdit() throws Exception {
        Fixture fixture = createFixture("mr-edit-permission");
        createMergeRequest(fixture, "feature/one", "Owned MR").andExpect(status().isCreated());
        Session developer = registerAndLogin("developer-mr-edit");
        addMember(fixture.repositoryId(), developer.username(), "DEVELOPER");

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", developer.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(
                    "Unauthorized edit",
                    null,
                    null,
                    0L
                ))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PERMISSION_DENIED"));

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(
                    "Owner edit",
                    null,
                    null,
                    0L
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Owner edit"));
    }

    @Test
    void privateRepositoryIsolationAndPublicCreationPermissionAreEnforced() throws Exception {
        Fixture fixture = createFixture("mr-isolation");
        Session outsider = registerAndLogin("outsider-mr-isolation");

        mockMvc.perform(get(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", outsider.authorization()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_NOT_FOUND"));

        jdbcTemplate.update(
            "UPDATE codetrove_repository SET visibility = 'PUBLIC' WHERE id = ?",
            Long.parseLong(fixture.repositoryId())
        );
        mockMvc.perform(post(endpoint(fixture.repositoryId()))
                .header("Authorization", outsider.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequest(
                    "Outsider MR",
                    null,
                    "feature/one",
                    "main"
                ))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PERMISSION_DENIED"));
    }

    @Test
    void diffUsesStoredSnapshotAndReturnsPatchMetadata() throws Exception {
        Fixture fixture = createFixture("mr-diff");
        createMergeRequest(fixture, "feature/one", "Diff MR").andExpect(status().isCreated());

        mockMvc.perform(get(endpoint(fixture.repositoryId()) + "/1/diff")
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCommit").value(fixture.mainCommit()))
            .andExpect(jsonPath("$.data.headCommit").value(fixture.featureOneCommit()))
            .andExpect(jsonPath("$.data.files.length()").value(1))
            .andExpect(jsonPath("$.data.files[0].status").value("ADD"))
            .andExpect(jsonPath("$.data.files[0].oldPath").doesNotExist())
            .andExpect(jsonPath("$.data.files[0].newPath").value("src/One.java"))
            .andExpect(jsonPath("$.data.files[0].additions").value(1))
            .andExpect(jsonPath("$.data.files[0].deletions").value(0))
            .andExpect(jsonPath("$.data.files[0].binary").value(false))
            .andExpect(jsonPath("$.data.files[0].patch").isString())
            .andExpect(jsonPath("$.data.files[0].truncated").value(false))
            .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    void diffClassifiesBinaryAndTruncatesOversizedPatch() throws Exception {
        Fixture fixture = createFixture("mr-diff-limits");
        String storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(fixture.repositoryId())
        );
        String limitedHead = createLimitBranch(Path.of(storagePath), fixture.mainCommit());

        createMergeRequest(fixture, "feature/limits", "Limited diff")
            .andExpect(status().isCreated());
        mockMvc.perform(get(endpoint(fixture.repositoryId()) + "/1/diff")
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.headCommit").value(limitedHead))
            .andExpect(jsonPath("$.data.files.length()").value(2))
            .andExpect(jsonPath("$.data.files[0].newPath").value("assets/blob.bin"))
            .andExpect(jsonPath("$.data.files[0].binary").value(true))
            .andExpect(jsonPath("$.data.files[0].patch").doesNotExist())
            .andExpect(jsonPath("$.data.files[1].newPath").value("large.txt"))
            .andExpect(jsonPath("$.data.files[1].binary").value(false))
            .andExpect(jsonPath("$.data.files[1].truncated").value(true))
            .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    void createsAndPaginatesGeneralAndValidDiffComments() throws Exception {
        Fixture fixture = createFixture("mr-comments");
        createMergeRequest(fixture, "feature/one", "Comments MR").andExpect(status().isCreated());
        String comments = commentsEndpoint(fixture.repositoryId(), 1);

        mockMvc.perform(post(comments)
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CommentRequest(
                    "Please add a boundary test.",
                    null
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.type").value("GENERAL"))
            .andExpect(jsonPath("$.data.body").value("Please add a boundary test."))
            .andExpect(jsonPath("$.data.position").doesNotExist());

        mockMvc.perform(post(comments)
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CommentRequest(
                    "Review the first line.",
                    new PositionRequest(fixture.featureOneCommit(), "src/One.java", "NEW", 1)
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.type").value("DIFF"))
            .andExpect(jsonPath("$.data.position.commitId").value(fixture.featureOneCommit()))
            .andExpect(jsonPath("$.data.position.filePath").value("src/One.java"))
            .andExpect(jsonPath("$.data.position.side").value("NEW"))
            .andExpect(jsonPath("$.data.position.line").value(1));

        String firstPage = mockMvc.perform(get(comments)
                .header("Authorization", fixture.owner().authorization())
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].type").value("GENERAL"))
            .andExpect(jsonPath("$.meta.nextCursor").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String cursor = objectMapper.readTree(firstPage).path("meta").path("nextCursor").asText();

        mockMvc.perform(get(comments)
                .header("Authorization", fixture.owner().authorization())
                .param("limit", "1")
                .param("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].type").value("DIFF"))
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist());
    }

    @Test
    void rejectsWrongCommitPathSideAndLineForDiffComments() throws Exception {
        Fixture fixture = createFixture("mr-invalid-comment");
        createMergeRequest(fixture, "feature/one", "Invalid comments MR")
            .andExpect(status().isCreated());
        String comments = commentsEndpoint(fixture.repositoryId(), 1);

        PositionRequest[] invalidPositions = {
            new PositionRequest(fixture.mainCommit(), "src/One.java", "NEW", 1),
            new PositionRequest(fixture.featureOneCommit(), "src/Missing.java", "NEW", 1),
            new PositionRequest(fixture.featureOneCommit(), "src/One.java", "OLD", 1),
            new PositionRequest(fixture.featureOneCommit(), "src/One.java", "NEW", 2)
        };
        for (PositionRequest position : invalidPositions) {
            mockMvc.perform(post(comments)
                    .header("Authorization", fixture.owner().authorization())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CommentRequest(
                        "Invalid position",
                        position
                    ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MR_DIFF_POSITION_INVALID"));
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_merge_request_comment",
            Integer.class
        )).isZero();
    }

    @Test
    void closedMergeRequestRejectsCommentsAndPublicOutsiderCannotComment() throws Exception {
        Fixture fixture = createFixture("mr-comment-access");
        createMergeRequest(fixture, "feature/one", "Comment access MR")
            .andExpect(status().isCreated());
        Session outsider = registerAndLogin("outsider-comment-access");
        jdbcTemplate.update(
            "UPDATE codetrove_repository SET visibility = 'PUBLIC' WHERE id = ?",
            Long.parseLong(fixture.repositoryId())
        );

        mockMvc.perform(get(endpoint(fixture.repositoryId()) + "/1/diff")
                .header("Authorization", outsider.authorization()))
            .andExpect(status().isOk());
        mockMvc.perform(post(commentsEndpoint(fixture.repositoryId(), 1))
                .header("Authorization", outsider.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CommentRequest("No membership", null))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PERMISSION_DENIED"));

        mockMvc.perform(patch(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRequest(null, null, "CLOSED", 0L))))
            .andExpect(status().isOk());
        mockMvc.perform(post(commentsEndpoint(fixture.repositoryId(), 1))
                .header("Authorization", fixture.owner().authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CommentRequest("After close", null))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_NOT_OPEN"));
    }

    @Test
    void successfulRefUpdateSynchronizesOpenMergeRequestHeadAndHistory() throws Exception {
        Fixture fixture = createFixture("mr-head-sync");
        createMergeRequest(fixture, "feature/one", "Head sync MR").andExpect(status().isCreated());
        String storagePath = repositoryStoragePath(fixture.repositoryId());
        String newHead;
        try (var repository = new FileRepositoryBuilder().setGitDir(Path.of(storagePath).toFile()).build()) {
            ObjectId next = createCommit(
                repository,
                ObjectId.fromString(fixture.featureOneCommit()),
                "src/Next.java",
                "class Next {}\n"
            );
            updateRef(repository, "refs/heads/feature/one", next);
            newHead = next.name();
        }
        RepositoryRefUpdate update = new RepositoryRefUpdate(
            "refs/heads/feature/one",
            fixture.featureOneCommit(),
            newHead,
            false
        );
        for (RepositoryRefUpdateListener listener : refUpdateListeners) {
            listener.afterRefsUpdated(Long.parseLong(fixture.repositoryId()), java.util.List.of(update));
        }

        mockMvc.perform(get(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.headCommit").value(newHead))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbcTemplate.queryForList(
            """
            SELECT commit_id FROM codetrove_merge_request_commit
            WHERE merge_request_id = (SELECT id FROM codetrove_merge_request WHERE repository_id = ? AND iid = 1)
            ORDER BY sequence_number
            """,
            String.class,
            Long.parseLong(fixture.repositoryId())
        )).containsExactly(fixture.featureOneCommit(), newHead);
    }

    @Test
    void mergeCreatesTwoParentCommitAndSupportsIdempotentReplay() throws Exception {
        Fixture fixture = createFixture("mr-merge");
        createMergeRequest(fixture, "feature/one", "Merge feature").andExpect(status().isCreated());
        String mergePath = endpoint(fixture.repositoryId()) + "/1/merge";
        MergeCommand command = new MergeCommand(fixture.featureOneCommit(), "MERGE_COMMIT");

        String firstBody = mockMvc.perform(post(mergePath)
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-success-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("MERGED"))
            .andExpect(jsonPath("$.data.idempotentReplay").value(false))
            .andExpect(jsonPath("$.data.mergeCommit").isString())
            .andReturn().getResponse().getContentAsString();
        String mergeCommit = objectMapper.readTree(firstBody).path("data").path("mergeCommit").asText();

        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(fixture.repositoryId())).toFile())
            .build(); var walk = new RevWalk(repository)) {
            assertThat(repository.resolve("refs/heads/main").name()).isEqualTo(mergeCommit);
            var commit = walk.parseCommit(ObjectId.fromString(mergeCommit));
            assertThat(commit.getParentCount()).isEqualTo(2);
            assertThat(commit.getParent(0).getId().name()).isEqualTo(fixture.mainCommit());
            assertThat(commit.getParent(1).getId().name()).isEqualTo(fixture.featureOneCommit());
        }

        mockMvc.perform(post(mergePath)
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-success-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mergeCommit").value(mergeCommit))
            .andExpect(jsonPath("$.data.idempotentReplay").value(true));

        mockMvc.perform(post(mergePath)
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-success-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureTwoCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void pendingOperationRecoversAfterGitRefWasAlreadyUpdated() throws Exception {
        Fixture fixture = createFixture("mr-pending-recovery");
        createMergeRequest(fixture, "feature/one", "Recover merge").andExpect(status().isCreated());
        String mergePath = endpoint(fixture.repositoryId()) + "/1/merge";
        MergeCommand command = new MergeCommand(fixture.featureOneCommit(), "MERGE_COMMIT");
        String firstBody = mockMvc.perform(post(mergePath)
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-recovery-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String mergeCommit = objectMapper.readTree(firstBody).path("data").path("mergeCommit").asText();

        jdbcTemplate.update(
            "UPDATE codetrove_merge_operation SET status = 'PENDING', completed_at = NULL WHERE idempotency_key = ?",
            "merge-recovery-001"
        );
        jdbcTemplate.update(
            """
            UPDATE codetrove_merge_request
            SET status = 'OPEN', merged_by = NULL, merged_at = NULL, merge_commit = NULL
            WHERE repository_id = ? AND iid = 1
            """,
            Long.parseLong(fixture.repositoryId())
        );

        mockMvc.perform(post(mergePath)
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-recovery-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(command)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("MERGED"))
            .andExpect(jsonPath("$.data.mergeCommit").value(mergeCommit))
            .andExpect(jsonPath("$.data.idempotentReplay").value(true));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM codetrove_merge_operation WHERE idempotency_key = ?",
            String.class,
            "merge-recovery-001"
        )).isEqualTo("SUCCEEDED");
    }

    @Test
    void reporterCannotMerge() throws Exception {
        Fixture fixture = createFixture("mr-reporter-merge");
        createMergeRequest(fixture, "feature/one", "Reporter denied").andExpect(status().isCreated());
        Session reporter = registerAndLogin("reporter-mr-merge");
        addMember(fixture.repositoryId(), reporter.username(), "REPORTER");

        mockMvc.perform(post(endpoint(fixture.repositoryId()) + "/1/merge")
                .header("Authorization", reporter.authorization())
                .header("Idempotency-Key", "merge-reporter-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureOneCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PERMISSION_DENIED"));
        assertThat(readBranch(fixture.repositoryId(), "main")).isEqualTo(fixture.mainCommit());
    }

    @Test
    void mergeRejectsWrongHeadWithoutChangingTarget() throws Exception {
        Fixture fixture = createFixture("mr-wrong-head");
        createMergeRequest(fixture, "feature/one", "Wrong head MR").andExpect(status().isCreated());

        mockMvc.perform(post(endpoint(fixture.repositoryId()) + "/1/merge")
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-wrong-head")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureTwoCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_HEAD_CHANGED"));
        assertThat(readBranch(fixture.repositoryId(), "main")).isEqualTo(fixture.mainCommit());
    }

    @Test
    void mergeConflictLeavesTargetAndMergeRequestOpen() throws Exception {
        Fixture fixture = createConflictFixture("mr-conflict");
        createMergeRequest(fixture, "feature/one", "Conflict MR").andExpect(status().isCreated());

        mockMvc.perform(post(endpoint(fixture.repositoryId()) + "/1/merge")
                .header("Authorization", fixture.owner().authorization())
                .header("Idempotency-Key", "merge-conflict-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MergeCommand(
                    fixture.featureOneCommit(),
                    "MERGE_COMMIT"
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("MR_MERGE_CONFLICT"));
        assertThat(readBranch(fixture.repositoryId(), "main")).isEqualTo(fixture.mainCommit());
        mockMvc.perform(get(endpoint(fixture.repositoryId()) + "/1")
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.mergeCommit").doesNotExist());
    }

    private Fixture createConflictFixture(String slug) throws Exception {
        Session owner = registerAndLogin("owner-" + slug);
        String repositoryId = createRepository(owner, slug);
        Path barePath = Path.of(repositoryStoragePath(repositoryId));
        try (var repository = new FileRepositoryBuilder().setGitDir(barePath.toFile()).build()) {
            ObjectId ancestor = repository.resolve("refs/heads/main");
            ObjectId target = createCommit(
                repository,
                ancestor,
                Map.of("README.md", "# Target change\n".getBytes(StandardCharsets.UTF_8)),
                false
            );
            ObjectId source = createCommit(
                repository,
                ancestor,
                Map.of("README.md", "# Source change\n".getBytes(StandardCharsets.UTF_8)),
                false
            );
            updateRef(repository, "refs/heads/main", target);
            updateRef(repository, "refs/heads/feature/one", source);
            return new Fixture(repositoryId, owner, target.name(), source.name(), source.name());
        }
    }

    private String repositoryStoragePath(String repositoryId) {
        return jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(repositoryId)
        );
    }

    private String readBranch(String repositoryId, String branch) throws Exception {
        try (var repository = new FileRepositoryBuilder()
            .setGitDir(Path.of(repositoryStoragePath(repositoryId)).toFile())
            .build()) {
            return repository.resolve("refs/heads/" + branch).name();
        }
    }

    private Fixture createFixture(String slug) throws Exception {
        Session owner = registerAndLogin("owner-" + slug);
        String repositoryId = createRepository(owner, slug);
        String storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(repositoryId)
        );
        CommitFixture commits = createFeatureBranches(Path.of(storagePath));
        return new Fixture(
            repositoryId,
            owner,
            commits.mainCommit(),
            commits.featureOneCommit(),
            commits.featureTwoCommit()
        );
    }

    private CommitFixture createFeatureBranches(Path barePath) throws Exception {
        try (var repository = new FileRepositoryBuilder().setGitDir(barePath.toFile()).build()) {
            ObjectId main = repository.resolve("refs/heads/main");
            ObjectId featureOne = createCommit(repository, main, "src/One.java", "class One {}\n");
            ObjectId featureTwo = createCommit(repository, main, "src/Two.java", "class Two {}\n");
            updateRef(repository, "refs/heads/feature/one", featureOne);
            updateRef(repository, "refs/heads/feature/two", featureTwo);
            return new CommitFixture(main.name(), featureOne.name(), featureTwo.name());
        }
    }

    private String createLimitBranch(Path barePath, String mainCommit) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/blob.bin", new byte[] {1, 2, 0, 3});
        files.put("large.txt", "line-content-0123456789\n".repeat(20000).getBytes(StandardCharsets.UTF_8));
        try (var repository = new FileRepositoryBuilder().setGitDir(barePath.toFile()).build()) {
            ObjectId commit = createCommit(repository, ObjectId.fromString(mainCommit), files);
            updateRef(repository, "refs/heads/feature/limits", commit);
            return commit.name();
        }
    }

    private ObjectId createCommit(
        org.eclipse.jgit.lib.Repository repository,
        ObjectId parent,
        String filePath,
        String content
    ) throws Exception {
        return createCommit(
            repository,
            parent,
            Map.of(filePath, content.getBytes(StandardCharsets.UTF_8))
        );
    }

    private ObjectId createCommit(
        org.eclipse.jgit.lib.Repository repository,
        ObjectId parent,
        Map<String, byte[]> files
    ) throws Exception {
        return createCommit(repository, parent, files, true);
    }

    private ObjectId createCommit(
        org.eclipse.jgit.lib.Repository repository,
        ObjectId parent,
        Map<String, byte[]> files,
        boolean includeOriginalReadme
    ) throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            if (includeOriginalReadme && !files.containsKey("README.md")) {
                ObjectId readme = repository.resolve("main^{tree}:README.md");
                DirCacheEntry readmeEntry = new DirCacheEntry("README.md");
                readmeEntry.setFileMode(FileMode.REGULAR_FILE);
                readmeEntry.setObjectId(readme);
                builder.add(readmeEntry);
            }
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ObjectId blob = inserter.insert(Constants.OBJ_BLOB, file.getValue());
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(blob);
                builder.add(entry);
            }
            builder.finish();
            ObjectId tree = cache.writeTree(inserter);
            PersonIdent identity = new PersonIdent(
                "CodeTrove MR Test",
                "mr@codetrove.local",
                Instant.parse("2026-10-05T02:00:00Z"),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            commit.setParentId(parent);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("test: create MR diff fixture");
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

    private org.springframework.test.web.servlet.ResultActions createMergeRequest(
        Fixture fixture,
        String sourceBranch,
        String title
    ) throws Exception {
        return mockMvc.perform(post(endpoint(fixture.repositoryId()))
            .header("Authorization", fixture.owner().authorization())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateRequest(
                title,
                "MR integration test",
                sourceBranch,
                "main"
            ))));
    }

    private Session registerAndLogin(String username) throws Exception {
        String password = "secure-password-for-" + username;
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Registration(username, password, username))))
            .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Login(username, password))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String token = objectMapper.readTree(body).path("data").path("accessToken").asText();
        return new Session(username, "Bearer " + token);
    }

    private String createRepository(Session owner, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RepositoryRequest(
                    "Repository " + slug,
                    slug,
                    "Merge request integration test",
                    "PRIVATE",
                    true
                ))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asText();
    }

    private void addMember(String repositoryId, String username, String role) {
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM codetrove_user WHERE username = ?",
            Long.class,
            username
        );
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_repository_member
                (repository_id, user_id, role, created_at, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            Long.parseLong(repositoryId),
            userId,
            role
        );
    }

    private String endpoint(String repositoryId) {
        return "/api/v1/repositories/" + repositoryId + "/merge-requests";
    }

    private String commentsEndpoint(String repositoryId, int iid) {
        return endpoint(repositoryId) + "/" + iid + "/comments";
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

    private record Registration(String username, String password, String displayName) {
    }

    private record Login(String username, String password) {
    }

    private record RepositoryRequest(
        String name,
        String slug,
        String description,
        String visibility,
        boolean initializeWithReadme
    ) {
    }

    private record CreateRequest(
        String title,
        String description,
        String sourceBranch,
        String targetBranch
    ) {
    }

    private record UpdateRequest(String title, String description, String status, Long version) {
    }

    private record CommentRequest(String body, PositionRequest position) {
    }

    private record PositionRequest(String commitId, String filePath, String side, int line) {
    }

    private record MergeCommand(String expectedHeadCommit, String strategy) {
    }

    private record Session(String username, String authorization) {
    }

    private record CommitFixture(String mainCommit, String featureOneCommit, String featureTwoCommit) {
    }

    private record Fixture(
        String repositoryId,
        Session owner,
        String mainCommit,
        String featureOneCommit,
        String featureTwoCommit
    ) {
    }
}
