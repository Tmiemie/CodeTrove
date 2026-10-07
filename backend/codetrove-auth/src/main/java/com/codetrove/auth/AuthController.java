package com.codetrove.auth;

import java.time.Instant;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthenticatedUser user = authService.register(
            request.username(),
            request.password(),
            request.displayName()
        );
        return ApiResponse.success(UserResponse.from(user), TraceContext.currentTraceId());
    }

    @PostMapping("/auth/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request.username(), request.password());
        LoginResponse response = new LoginResponse(
            "Bearer",
            result.accessToken(),
            result.expiresAt(),
            UserResponse.from(result.user())
        );
        return ApiResponse.success(response, TraceContext.currentTraceId());
    }

    @GetMapping("/users/me")
    ApiResponse<UserResponse> currentUser(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(UserResponse.from(user), TraceContext.currentTraceId());
    }

    record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?", message = "must be URL-safe")
        String username,
        @NotBlank
        @Size(min = 12, max = 72)
        String password,
        @NotBlank
        @Size(max = 128)
        String displayName
    ) {
    }

    record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 72) String password
    ) {
    }

    record LoginResponse(
        String tokenType,
        String accessToken,
        Instant expiresAt,
        UserResponse user
    ) {
    }

    record UserResponse(String id, String username, String displayName, String status) {
        static UserResponse from(AuthenticatedUser user) {
            return new UserResponse(
                Long.toString(user.id()),
                user.username(),
                user.displayName(),
                user.status()
            );
        }
    }
}
