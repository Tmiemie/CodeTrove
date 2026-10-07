package com.codetrove.bootstrap.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GitSmartHttpTests {

    private static final Path STORAGE_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "codetrove-test-repositories"
    );
    private static final String PASSWORD = "secure-password-for-git-user";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanState() throws IOException {
        cleanDatabaseAndStorage();
    }

    @AfterEach
    void cleanAfterTest() throws IOException {
        cleanDatabaseAndStorage();
    }

    @Test
    void missingCredentialsReturnBasicChallenge() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(gitInfoRefs("missing", "repo"))
            .GET()
            .build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
            .hasValueSatisfying(value -> assertThat(value)
                .contains("Basic realm=\"CodeTrove Git\""));
    }

    @Test
    void wrongPasswordIsRejectedBeforeRepositoryResolution() throws Exception {
        register("git-wrong", PASSWORD);

        HttpResponse<String> response = send(HttpRequest.newBuilder(gitInfoRefs("git-wrong", "repo"))
            .header("Authorization", basic("git-wrong", "incorrect-password"))
            .GET()
            .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void authenticatedOwnerCanAdvertisePrivateRepositoryForClone() throws Exception {
        String token = registerAndLogin("git-owner", PASSWORD);
        createRepository(token, "private-repo", "PRIVATE");

        HttpResponse<byte[]> response = httpClient.send(
            HttpRequest.newBuilder(gitInfoRefs("git-owner", "private-repo"))
                .header("Authorization", basic("git-owner", PASSWORD))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
            .hasValueSatisfying(value -> assertThat(value).contains("application/x-git-upload-pack-advertisement"));
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
            .contains("refs/heads/main");
    }

    @Test
    void outsiderCannotDiscoverPrivateRepository() throws Exception {
        String ownerToken = registerAndLogin("git-private", PASSWORD);
        register("git-outsider", PASSWORD);
        createRepository(ownerToken, "secret-repo", "PRIVATE");

        HttpResponse<String> response = send(HttpRequest.newBuilder(gitInfoRefs("git-private", "secret-repo"))
            .header("Authorization", basic("git-outsider", PASSWORD))
            .GET()
            .build());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void oversizedGitRequestIsRejectedBeforeAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(baseUrl() + "/git/owner/repo.git/git-receive-pack")
            )
            .header("Content-Type", "application/x-git-receive-pack-request")
            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4097)))
            .build());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    private void register(String username, String password) throws Exception {
        HttpResponse<String> response = send(jsonPost(
            "/api/v1/auth/register",
            objectMapper.writeValueAsString(new Registration(username, password, username)),
            null
        ));
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private String registerAndLogin(String username, String password) throws Exception {
        register(username, password);
        HttpResponse<String> response = send(jsonPost(
            "/api/v1/auth/login",
            objectMapper.writeValueAsString(new Login(username, password)),
            null
        ));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("data").path("accessToken").asText();
    }

    private void createRepository(String token, String slug, String visibility) throws Exception {
        HttpResponse<String> response = send(jsonPost(
            "/api/v1/repositories",
            objectMapper.writeValueAsString(new RepositoryRequest(
                "Repository " + slug,
                slug,
                "Git Smart HTTP integration test",
                visibility,
                true
            )),
            "Bearer " + token
        ));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("data").path("gitHttpUrl").asText())
            .isEqualTo("/git/" + body.path("data").path("owner").asText() + "/" + slug + ".git");
    }

    private HttpRequest jsonPost(String path, String body, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder.build();
    }

    private URI gitInfoRefs(String owner, String slug) {
        return URI.create(baseUrl() + "/git/" + owner + "/" + slug
            + ".git/info/refs?service=git-upload-pack");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private String basic(String username, String password) {
        String value = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void cleanDatabaseAndStorage() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_repository_member");
        jdbcTemplate.update("DELETE FROM codetrove_repository");
        jdbcTemplate.update("DELETE FROM codetrove_user");
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
}
