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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GitCliEndToEndTests {

    private static final Path STORAGE_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "codetrove-test-repositories"
    );
    private static final String PASSWORD = "secure-password-for-git-cli";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    private Path workDirectory;

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
    void realGitCliCloneFetchPushAndAuthorizationFlow() throws Exception {
        Account owner = registerAndLogin("git-cli-owner");
        Account reporter = registerAndLogin("git-cli-reporter");
        long repositoryId = createRepository(owner.token(), "smart-http");
        addRepositoryMember(repositoryId, reporter.userId(), "REPORTER");

        Path ownerClone = workDirectory.resolve("owner-clone");
        CommandResult clone = git(
            workDirectory,
            "clone",
            authenticatedUrl(owner.username(), "smart-http"),
            ownerClone.toString()
        );
        assertThat(clone.exitCode()).as(clone.output()).isZero();
        assertThat(Files.readString(ownerClone.resolve("README.md"))).contains("CodeTrove Repository");

        assertGitSuccess(ownerClone, "config", "user.name", "CodeTrove Test");
        assertGitSuccess(ownerClone, "config", "user.email", "test@codetrove.local");
        assertGitSuccess(ownerClone, "switch", "-c", "feature/git-smart-http");
        Files.writeString(ownerClone.resolve("feature.txt"), "real Git Smart HTTP push\n");
        assertGitSuccess(ownerClone, "add", "feature.txt");
        assertGitSuccess(ownerClone, "commit", "-m", "test: verify Git Smart HTTP");

        CommandResult featurePush = git(
            ownerClone,
            "push",
            "origin",
            "HEAD:refs/heads/feature/git-smart-http"
        );
        assertThat(featurePush.exitCode()).as(featurePush.output()).isZero();

        Path fetchClone = workDirectory.resolve("fetch-clone");
        CommandResult secondClone = git(
            workDirectory,
            "clone",
            authenticatedUrl(owner.username(), "smart-http"),
            fetchClone.toString()
        );
        assertThat(secondClone.exitCode()).as(secondClone.output()).isZero();
        assertGitSuccess(fetchClone, "fetch", "origin", "feature/git-smart-http");
        CommandResult fetchedRef = git(
            fetchClone,
            "rev-parse",
            "refs/remotes/origin/feature/git-smart-http"
        );
        assertThat(fetchedRef.exitCode()).as(fetchedRef.output()).isZero();
        assertThat(fetchedRef.output().trim()).matches("[0-9a-f]{40}");

        CommandResult protectedPush = git(ownerClone, "push", "origin", "HEAD:refs/heads/main");
        assertThat(protectedPush.exitCode()).as(protectedPush.output()).isNotZero();
        assertThat(protectedPush.output()).contains("protected branch requires merge request");

        Path reporterClone = workDirectory.resolve("reporter-clone");
        CommandResult reporterRead = git(
            workDirectory,
            "clone",
            authenticatedUrl(reporter.username(), "smart-http"),
            reporterClone.toString()
        );
        assertThat(reporterRead.exitCode()).as(reporterRead.output()).isZero();
        assertGitSuccess(reporterClone, "config", "user.name", "Reporter Test");
        assertGitSuccess(reporterClone, "config", "user.email", "reporter@codetrove.local");
        assertGitSuccess(reporterClone, "switch", "-c", "feature/reporter-denied");
        Files.writeString(reporterClone.resolve("reporter.txt"), "must not be pushed\n");
        assertGitSuccess(reporterClone, "add", "reporter.txt");
        assertGitSuccess(reporterClone, "commit", "-m", "test: reporter push denied");

        CommandResult reporterPush = git(
            reporterClone,
            "push",
            "origin",
            "HEAD:refs/heads/feature/reporter-denied"
        );
        assertThat(reporterPush.exitCode()).as(reporterPush.output()).isNotZero();
        assertThat(reporterPush.output()).containsAnyOf(
            "Authentication failed",
            "403",
            "not authorized",
            "unable to access"
        );
        CommandResult missingReporterBranch = git(
            ownerClone,
            "ls-remote",
            "origin",
            "refs/heads/feature/reporter-denied"
        );
        assertThat(missingReporterBranch.exitCode()).as(missingReporterBranch.output()).isZero();
        assertThat(missingReporterBranch.output()).isBlank();
    }

    private Account registerAndLogin(String username) throws Exception {
        HttpResponse<String> registration = send(jsonPost(
            "/api/v1/auth/register",
            objectMapper.writeValueAsString(new Registration(username, PASSWORD, username)),
            null
        ));
        assertThat(registration.statusCode()).isEqualTo(201);
        long userId = Long.parseLong(
            objectMapper.readTree(registration.body()).path("data").path("id").asText()
        );

        HttpResponse<String> login = send(jsonPost(
            "/api/v1/auth/login",
            objectMapper.writeValueAsString(new Login(username, PASSWORD)),
            null
        ));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = objectMapper.readTree(login.body()).path("data").path("accessToken").asText();
        return new Account(userId, username, token);
    }

    private long createRepository(String token, String slug) throws Exception {
        HttpResponse<String> response = send(jsonPost(
            "/api/v1/repositories",
            objectMapper.writeValueAsString(new RepositoryRequest(
                "Git Smart HTTP",
                slug,
                "Real Git CLI integration test",
                "PRIVATE",
                true
            )),
            "Bearer " + token
        ));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return Long.parseLong(body.path("data").path("id").asText());
    }

    private void addRepositoryMember(long repositoryId, long userId, String role) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO codetrove_repository_member
                (repository_id, user_id, role, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            repositoryId,
            userId,
            role,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now)
        );
    }

    private String authenticatedUrl(String username, String slug) {
        return "http://" + username + ":" + PASSWORD + "@127.0.0.1:" + port
            + "/git/git-cli-owner/" + slug + ".git";
    }

    private void assertGitSuccess(Path directory, String... arguments) throws Exception {
        CommandResult result = git(directory, arguments);
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    private CommandResult git(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("credential.helper=");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Git command exceeded 30 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
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

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private void cleanDatabaseAndStorage() throws IOException {
        jdbcTemplate.update("DELETE FROM codetrove_repository_member");
        jdbcTemplate.update("DELETE FROM codetrove_repository");
        jdbcTemplate.update("DELETE FROM codetrove_user");
        new WindowCacheConfig().install();
        deleteRecursivelyWithRetry(STORAGE_ROOT);
    }

    private void deleteRecursivelyWithRetry(Path path) throws IOException {
        IOException failure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                deleteRecursively(path);
                return;
            } catch (IOException exception) {
                failure = exception;
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while releasing Git pack files", interrupted);
                }
            }
        }
        throw failure;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path current : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
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

    private record Account(long userId, String username, String token) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
