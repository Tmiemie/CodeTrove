package com.codetrove.bootstrap.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.codetrove.repository.RepositoryAuthorizationService;
import com.codetrove.repository.RepositoryPermission;
import com.codetrove.repository.RepositoryRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
class RepositoryApiTests {

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
    private RepositoryAuthorizationService authorizationService;

    @BeforeEach
    void cleanState() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_repository_member");
        jdbcTemplate.update("DELETE FROM codetrove_repository");
        jdbcTemplate.update("DELETE FROM codetrove_user");
        deleteRecursively(STORAGE_ROOT);
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_repository_member");
        jdbcTemplate.update("DELETE FROM codetrove_repository");
        jdbcTemplate.update("DELETE FROM codetrove_user");
        deleteRecursively(STORAGE_ROOT);
    }

    @Test
    void repositoryEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/repositories"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(repositoryRequest("private-repo", "PRIVATE", true)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void ownerCanCreateBareRepositoryWithInitialReadme() throws Exception {
        Session owner = registerAndLogin("owner-one");

        String body = mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(repositoryRequest("quality-platform", "PRIVATE", true)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.owner").value("owner-one"))
            .andExpect(jsonPath("$.data.currentUserRole").value("OWNER"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.defaultBranch").value("main"))
            .andExpect(jsonPath("$.data.gitHttpUrl").value("/git/owner-one/quality-platform.git"))
            .andExpect(jsonPath("$.data.storagePath").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String repositoryId = objectMapper.readTree(body).path("data").path("id").asText();
        String storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE id = ?",
            String.class,
            Long.parseLong(repositoryId)
        );
        Path barePath = Path.of(storagePath);
        assertThat(barePath.normalize().startsWith(STORAGE_ROOT.toAbsolutePath().normalize())).isTrue();
        assertThat(barePath.getFileName().toString()).isEqualTo("quality-platform.git");

        try (var repository = new FileRepositoryBuilder().setGitDir(barePath.toFile()).build()) {
            assertThat(repository.isBare()).isTrue();
            assertThat(repository.resolve("refs/heads/main")).isNotNull();
            assertThat(repository.resolve(Constants.HEAD)).isNotNull();
            assertThat(repository.open(repository.resolve("main^{tree}:README.md")).getBytes())
                .asString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("CodeTrove Repository");
        }
    }

    @Test
    void duplicateSlugReturnsConflictWithoutDeletingOriginalRepository() throws Exception {
        Session owner = registerAndLogin("owner-two");
        createRepository(owner, "same-slug", "PRIVATE", true)
            .andExpect(status().isCreated());

        String storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM codetrove_repository WHERE slug = 'same-slug'",
            String.class
        );
        mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(repositoryRequest("same-slug", "PRIVATE", false)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_SLUG_CONFLICT"));

        assertThat(Path.of(storagePath)).isDirectory();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM codetrove_repository WHERE slug = 'same-slug'",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void privateRepositoriesAreHiddenAndPublicRepositoriesAreVisible() throws Exception {
        Session owner = registerAndLogin("owner-three");
        Session outsider = registerAndLogin("outsider");
        String privateId = repositoryId(createRepository(owner, "private-one", "PRIVATE", false));
        String publicId = repositoryId(createRepository(owner, "public-one", "PUBLIC", false));

        mockMvc.perform(get("/api/v1/repositories").header("Authorization", outsider.authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(publicId))
            .andExpect(jsonPath("$.data[0].currentUserRole").doesNotExist());

        mockMvc.perform(get("/api/v1/repositories/{id}", privateId)
                .header("Authorization", outsider.authorization()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/{id}", publicId)
                .header("Authorization", outsider.authorization()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.data.currentUserRole").doesNotExist());
    }

    @Test
    void invalidSlugIsRejectedBeforeAnyStoragePathIsCreated() throws Exception {
        Session owner = registerAndLogin("owner-four");

        mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(repositoryRequest("../escape", "PRIVATE", false)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(Files.exists(STORAGE_ROOT.resolve("escape.git"))).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM codetrove_repository", Integer.class))
            .isZero();
    }

    @Test
    void detailRejectsNonNumericRepositoryId() throws Exception {
        Session owner = registerAndLogin("owner-invalid-id");

        mockMvc.perform(get("/api/v1/repositories/not-a-number")
                .header("Authorization", owner.authorization()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listUsesOpaqueCursorWithoutDuplicates() throws Exception {
        Session owner = registerAndLogin("owner-cursor");
        String firstCreatedId = repositoryId(createRepository(owner, "cursor-one", "PRIVATE", false));
        String secondCreatedId = repositoryId(createRepository(owner, "cursor-two", "PRIVATE", false));

        String firstPageBody = mockMvc.perform(get("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(secondCreatedId))
            .andExpect(jsonPath("$.meta.nextCursor").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String cursor = objectMapper.readTree(firstPageBody).path("meta").path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .param("limit", "1")
                .param("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(firstCreatedId))
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .param("cursor", "not-a-valid-cursor"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listRejectsLimitAboveMaximum() throws Exception {
        Session owner = registerAndLogin("owner-limit");

        mockMvc.perform(get("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .param("limit", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void existingStorageDirectoryIsNotReusedOrDeleted() throws Exception {
        Session owner = registerAndLogin("owner-orphan");
        Path existingPath = STORAGE_ROOT.resolve("owner-orphan").resolve("orphan.git");
        Files.createDirectories(existingPath);
        Path marker = existingPath.resolve("do-not-delete.txt");
        Files.writeString(marker, "existing data");

        mockMvc.perform(post("/api/v1/repositories")
                .header("Authorization", owner.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(repositoryRequest("orphan", "PRIVATE", false)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("REPOSITORY_INITIALIZATION_FAILED"));

        assertThat(marker).exists();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM codetrove_repository", Integer.class))
            .isZero();
    }

    @Test
    void rolePermissionMatrixMatchesDomainContract() {
        assertAllowed(RepositoryRole.OWNER, RepositoryPermission.values());
        assertAllowed(
            RepositoryRole.MAINTAINER,
            RepositoryPermission.READ,
            RepositoryPermission.CREATE_MERGE_REQUEST,
            RepositoryPermission.COMMENT,
            RepositoryPermission.PUSH,
            RepositoryPermission.MERGE,
            RepositoryPermission.MODIFY_RULES
        );
        assertDenied(
            RepositoryRole.MAINTAINER,
            RepositoryPermission.MANAGE_MEMBERS,
            RepositoryPermission.MANAGE_REPOSITORY
        );
        assertAllowed(
            RepositoryRole.DEVELOPER,
            RepositoryPermission.READ,
            RepositoryPermission.CREATE_MERGE_REQUEST,
            RepositoryPermission.COMMENT,
            RepositoryPermission.PUSH,
            RepositoryPermission.MERGE
        );
        assertDenied(
            RepositoryRole.DEVELOPER,
            RepositoryPermission.MODIFY_RULES,
            RepositoryPermission.MANAGE_MEMBERS,
            RepositoryPermission.MANAGE_REPOSITORY
        );
        assertAllowed(
            RepositoryRole.REPORTER,
            RepositoryPermission.READ,
            RepositoryPermission.CREATE_MERGE_REQUEST,
            RepositoryPermission.COMMENT
        );
        assertDenied(
            RepositoryRole.REPORTER,
            RepositoryPermission.PUSH,
            RepositoryPermission.MERGE,
            RepositoryPermission.MODIFY_RULES,
            RepositoryPermission.MANAGE_MEMBERS,
            RepositoryPermission.MANAGE_REPOSITORY
        );
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
        String visibility,
        boolean initializeWithReadme
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/repositories")
            .header("Authorization", owner.authorization())
            .contentType(MediaType.APPLICATION_JSON)
            .content(repositoryRequest(slug, visibility, initializeWithReadme)));
    }

    private String repositoryId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.path("data").path("id").asText();
    }

    private String repositoryRequest(String slug, String visibility, boolean initializeWithReadme)
        throws Exception {
        return objectMapper.writeValueAsString(new RepositoryRequest(
            "Repository " + slug,
            slug,
            "Repository integration test",
            visibility,
            initializeWithReadme
        ));
    }

    private void assertAllowed(RepositoryRole role, RepositoryPermission... permissions) {
        for (RepositoryPermission permission : permissions) {
            assertThat(authorizationService.isAllowed(role, permission))
                .as("%s should allow %s", role, permission)
                .isTrue();
        }
    }

    private void assertDenied(RepositoryRole role, RepositoryPermission... permissions) {
        for (RepositoryPermission permission : permissions) {
            assertThat(authorizationService.isAllowed(role, permission))
                .as("%s should deny %s", role, permission)
                .isFalse();
        }
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
}
