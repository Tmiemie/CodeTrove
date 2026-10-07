package com.codetrove.repository;

import java.time.Instant;
import java.util.List;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories")
@Validated
class RepositoryController {

    private final RepositoryService repositoryService;

    RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RepositoryResponse> create(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody CreateRepositoryRequest request
    ) {
        RepositoryRecord repository = repositoryService.create(
            user,
            request.name(),
            request.slug(),
            request.description(),
            request.visibility(),
            request.initializeWithReadme()
        );
        return ApiResponse.success(RepositoryResponse.from(repository), TraceContext.currentTraceId());
    }

    @GetMapping
    ApiResponse<List<RepositoryResponse>> list(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        RepositoryService.RepositoryPage page = repositoryService.listVisible(user, cursor, limit);
        List<RepositoryResponse> repositories = page.repositories()
            .stream()
            .map(RepositoryResponse::from)
            .toList();
        return ApiResponse.paged(repositories, TraceContext.currentTraceId(), page.nextCursor());
    }

    @GetMapping("/{repositoryId}")
    ApiResponse<RepositoryResponse> detail(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId
    ) {
        RepositoryRecord repository = repositoryService.findVisible(repositoryId, user);
        return ApiResponse.success(RepositoryResponse.from(repository), TraceContext.currentTraceId());
    }

    @GetMapping("/{repositoryId}/branches")
    ApiResponse<List<GitRepositoryBrowser.BranchView>> branches(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId
    ) {
        return ApiResponse.success(
            repositoryService.listBranches(repositoryId, user),
            TraceContext.currentTraceId()
        );
    }

    @GetMapping("/{repositoryId}/tree")
    ApiResponse<GitRepositoryBrowser.TreePage> tree(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @RequestParam @NotBlank @Size(max = 255) String ref,
        @RequestParam(defaultValue = "") @Size(max = 1024) String path,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.success(
            repositoryService.browseTree(repositoryId, user, ref, path, cursor, limit),
            TraceContext.currentTraceId()
        );
    }

    @GetMapping("/{repositoryId}/blob")
    ApiResponse<GitRepositoryBrowser.BlobView> blob(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @RequestParam @NotBlank @Size(max = 255) String ref,
        @RequestParam @NotBlank @Size(max = 1024) String path
    ) {
        return ApiResponse.success(
            repositoryService.readBlob(repositoryId, user, ref, path),
            TraceContext.currentTraceId()
        );
    }

    record CreateRepositoryRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank
        @Size(min = 1, max = 100)
        @Pattern(
            regexp = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?",
            message = "must contain lowercase letters, numbers, or hyphens"
        )
        String slug,
        @Size(max = 500) String description,
        @NotNull RepositoryVisibility visibility,
        boolean initializeWithReadme
    ) {
    }

    record RepositoryResponse(
        String id,
        String owner,
        String name,
        String slug,
        String description,
        String visibility,
        String defaultBranch,
        String status,
        String currentUserRole,
        String gitHttpUrl,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        static RepositoryResponse from(RepositoryRecord repository) {
            return new RepositoryResponse(
                Long.toString(repository.id()),
                repository.ownerUsername(),
                repository.name(),
                repository.slug(),
                repository.description(),
                repository.visibility().name(),
                repository.defaultBranch(),
                repository.status().name(),
                repository.currentUserRole() == null ? null : repository.currentUserRole().name(),
                "/git/" + repository.ownerUsername() + "/" + repository.slug() + ".git",
                repository.version(),
                repository.createdAt(),
                repository.updatedAt()
            );
        }
    }
}
