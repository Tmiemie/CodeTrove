package com.codetrove.bootstrap.repository;

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

import com.fasterxml.jackson.databind.JsonNode;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepositoryBrowseApiTests {

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

    @BeforeEach
    void cleanState() throws IOException {
        cleanDatabaseAndStorage();
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        cleanDatabaseAndStorage();
    }

    @Test
    void branchesReturnOnlyLocalBranchesWithCommitMetadata() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-branches", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories/{id}/branches", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].name").value("feature/browse"))
            .andExpect(jsonPath("$.data[0].commitId").value(fixture.commitId()))
            .andExpect(jsonPath("$.data[0].default").value(false))
            .andExpect(jsonPath("$.data[1].name").value("main"))
            .andExpect(jsonPath("$.data[1].commitMessage").value("test: add browsing fixture"))
            .andExpect(jsonPath("$.data[1].authorName").value("CodeTrove Browse Test"))
            .andExpect(jsonPath("$.data[1].authoredAt").isString())
            .andExpect(jsonPath("$.data[1].default").value(true));
    }

    @Test
    void treeSupportsStableCursorPaginationAndNestedDirectory() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-tree", "PRIVATE");

        String firstPage = mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commitId").value(fixture.commitId()))
            .andExpect(jsonPath("$.data.path").value(""))
            .andExpect(jsonPath("$.data.entries.length()").value(2))
            .andExpect(jsonPath("$.data.entries[0].name").value("README.md"))
            .andExpect(jsonPath("$.data.entries[0].type").value("BLOB"))
            .andExpect(jsonPath("$.data.nextCursor").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String cursor = objectMapper.readTree(firstPage).path("data").path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("limit", "2")
                .param("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.entries.length()").value(2))
            .andExpect(jsonPath("$.data.entries[1].name").value("src"))
            .andExpect(jsonPath("$.data.entries[1].type").value("TREE"))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", fixture.commitId())
                .param("path", "src"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commitId").value(fixture.commitId()))
            .andExpect(jsonPath("$.data.path").value("src"))
            .andExpect(jsonPath("$.data.entries.length()").value(2))
            .andExpect(jsonPath("$.data.entries[0].path").value("src/App.java"))
            .andExpect(jsonPath("$.data.entries[1].path").value("src/util"))
            .andExpect(jsonPath("$.data.entries[1].type").value("TREE"));
    }

    @Test
    void blobIncludesSmallUtf8TextAndClassifiesBinaryAndLargeText() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-blob", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories/{id}/blob", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("path", "src/App.java"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.binary").value(false))
            .andExpect(jsonPath("$.data.contentIncluded").value(true))
            .andExpect(jsonPath("$.data.encoding").value("UTF-8"))
            .andExpect(jsonPath("$.data.content").value("class App {}\n"))
            .andExpect(jsonPath("$.data.notIncludedReason").doesNotExist());

        mockMvc.perform(get("/api/v1/repositories/{id}/blob", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("path", "binary.dat"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.binary").value(true))
            .andExpect(jsonPath("$.data.contentIncluded").value(false))
            .andExpect(jsonPath("$.data.content").doesNotExist())
            .andExpect(jsonPath("$.data.notIncludedReason").value("BINARY"));

        mockMvc.perform(get("/api/v1/repositories/{id}/blob", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("path", "large.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.binary").value(false))
            .andExpect(jsonPath("$.data.contentIncluded").value(false))
            .andExpect(jsonPath("$.data.content").doesNotExist())
            .andExpect(jsonPath("$.data.notIncludedReason").value("TOO_LARGE"));
    }

    @Test
    void invalidRefsHaveStableValidationOrNotFoundErrors() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-ref", "PRIVATE");

        for (String invalid : new String[] {"HEAD~1", "refs/tags/v1", fixture.commitId().substring(0, 8)}) {
            mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                    .header("Authorization", fixture.owner().authorization())
                    .param("ref", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "missing-branch"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_REF_NOT_FOUND"));
    }

    @Test
    void invalidPathsAndCursorsAreRejectedBeforeGitLookup() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-path", "PRIVATE");

        for (String invalid : new String[] {"../README.md", "/README.md", "src\\App.java", "src//App.java", "src/", "."}) {
            mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                    .header("Authorization", fixture.owner().authorization())
                    .param("ref", "main")
                    .param("path", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("cursor", "not-valid-base64!"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingOrWrongObjectTypesReturnPathNotFound() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-missing", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("path", "README.md"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PATH_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/{id}/blob", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("path", "src"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PATH_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/{id}/blob", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("path", "missing.txt"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_PATH_NOT_FOUND"));
    }

    @Test
    void browseEndpointsPreservePrivateIsolationAndPublicRead() throws Exception {
        BrowseFixture privateFixture = createBrowseFixture("private-browse", "PRIVATE");
        BrowseFixture publicFixture = createBrowseFixture("public-browse", "PUBLIC");
        Session outsider = registerAndLogin("browse-outsider");

        mockMvc.perform(get("/api/v1/repositories/{id}/branches", privateFixture.repositoryId())
                .header("Authorization", outsider.authorization()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", publicFixture.repositoryId())
                .header("Authorization", outsider.authorization())
                .param("ref", "main"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.entries.length()").value(4));

        mockMvc.perform(get("/api/v1/repositories/{id}/blob", publicFixture.repositoryId())
                .header("Authorization", outsider.authorization())
                .param("ref", "main")
                .param("path", "README.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contentIncluded").value(true));
    }

    @Test
    void browseEndpointsRequireAuthenticationAndValidateLimits() throws Exception {
        BrowseFixture fixture = createBrowseFixture("browse-auth", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories/{id}/branches", fixture.repositoryId()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        mockMvc.perform(get("/api/v1/repositories/{id}/tree", fixture.repositoryId())
                .header("Authorization", fixture.owner().authorization())
                .param("ref", "main")
                .param("limit", "501"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private BrowseFixture createBrowseFixture(String slug, String visibility) throws Exception {
        Session owner = registerAndLogin("owner-" + slug);
        String repositoryId = repositoryId(createRepository(owner, slug, visibility));
        String storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(repositoryId)
        );
        String commitId = populateBrowseFixture(Path.of(storagePath));
        return new BrowseFixture(repositoryId, owner, commitId);
    }

    private String populateBrowseFixture(Path barePath) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", "# Browse Fixture\n".getBytes(StandardCharsets.UTF_8));
        files.put("binary.dat", new byte[] {1, 2, 0, 3});
        files.put("large.txt", "x".repeat(2048).getBytes(StandardCharsets.UTF_8));
        files.put("src/App.java", "class App {}\n".getBytes(StandardCharsets.UTF_8));
        files.put("src/util/Helper.java", "class Helper {}\n".getBytes(StandardCharsets.UTF_8));

        try (var repository = new FileRepositoryBuilder().setGitDir(barePath.toFile()).build();
             var inserter = repository.newObjectInserter()) {
            ObjectId parent = repository.resolve("refs/heads/main");
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ObjectId blob = inserter.insert(Constants.OBJ_BLOB, file.getValue());
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(blob);
                builder.add(entry);
            }
            builder.finish();
            ObjectId treeId = cache.writeTree(inserter);
            PersonIdent identity = new PersonIdent(
                "CodeTrove Browse Test",
                "browse@codetrove.local",
                Instant.parse("2026-10-05T01:00:00Z"),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setParentId(parent);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("test: add browsing fixture");
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            updateRef(repository, "refs/heads/main", commitId);
            updateRef(repository, "refs/heads/feature/browse", commitId);
            return commitId.name();
        }
    }

    private void updateRef(org.eclipse.jgit.lib.Repository repository, String name, ObjectId commitId)
        throws IOException {
        RefUpdate update = repository.updateRef(name);
        update.setNewObjectId(commitId);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.update();
        assertThat(result).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED);
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
        return new Session("Bearer " + token);
    }

    private org.springframework.test.web.servlet.ResultActions createRepository(
        Session owner,
        String slug,
        String visibility
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/repositories")
            .header("Authorization", owner.authorization())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new RepositoryRequest(
                "Repository " + slug,
                slug,
                "Repository browse integration test",
                visibility,
                true
            ))));
    }

    private String repositoryId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.path("data").path("id").asText();
    }

    private void cleanDatabaseAndStorage() throws IOException {
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

    private record Session(String authorization) {
    }

    private record BrowseFixture(String repositoryId, Session owner, String commitId) {
    }
}
