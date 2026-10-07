package com.codetrove.bootstrap.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
class AuthApiTests {

    private static final String TEST_JWT_SECRET = "test-only-secret-key-with-at-least-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUsers() {
        jdbcTemplate.update("DELETE FROM codetrove_user");
    }

    @Test
    void registerHashesPasswordAndDoesNotExposeHash() throws Exception {
        String responseBody = register("Alice", "a-secure-password", "Alice Chen")
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Trace-Id", matchesPattern("[a-f0-9]{32}")))
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.username").value("alice"))
            .andExpect(jsonPath("$.data.displayName").value("Alice Chen"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(response.path("data").path("id").asText()).isNotBlank();
        String passwordHash = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM codetrove_user WHERE username = ?",
            String.class,
            "alice"
        );
        assertThat(passwordHash).startsWith("$2a$12$").doesNotContain("a-secure-password");
    }

    @Test
    void loginReturnsAccessTokenThatAuthenticatesCurrentUser() throws Exception {
        register("alice", "a-secure-password", "Alice Chen").andExpect(status().isCreated());

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"ALICE","password":"a-secure-password"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.accessToken").isString())
            .andExpect(jsonPath("$.data.expiresAt").isString())
            .andExpect(jsonPath("$.data.user.username").value("alice"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String accessToken = objectMapper.readTree(loginBody).path("data").path("accessToken").asText();
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("alice"))
            .andExpect(jsonPath("$.data.displayName").value("Alice Chen"));
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        register("alice", "a-secure-password", "Alice Chen").andExpect(status().isCreated());
        register("ALICE", "another-secure-password", "Another Alice")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("USERNAME_CONFLICT"));
    }

    @Test
    void invalidCredentialsDoNotRevealWhetherUserExists() throws Exception {
        register("alice", "a-secure-password", "Alice Chen").andExpect(status().isCreated());

        assertInvalidCredentials("alice", "wrong-password-value");
        assertInvalidCredentials("missing-user", "wrong-password-value");
    }

    @Test
    void currentUserRejectsMissingAndInvalidTokens() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid.jwt.token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void currentUserRejectsExpiredToken() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
            .issuer("codetrove-test")
            .subject("123")
            .claim("username", "alice")
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now.minusSeconds(120)))
            .expiration(Date.from(now.minusSeconds(60)))
            .signWith(key)
            .compact();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
    }

    private org.springframework.test.web.servlet.ResultActions register(
        String username,
        String password,
        String displayName
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new Registration(username, password, displayName))));
    }

    private void assertInvalidCredentials(String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Login(username, password))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.error.message").value("Invalid username or password"));
    }

    private record Registration(String username, String password, String displayName) {
    }

    private record Login(String username, String password) {
    }
}
