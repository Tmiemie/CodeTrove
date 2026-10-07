package com.codetrove.mergerequest;

import java.time.Instant;
import java.util.List;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/merge-requests")
@Validated
class MergeRequestController {

    private final MergeRequestService mergeRequestService;
    private final MergeRequestCollaborationService collaborationService;
    private final MergeRequestMergeService mergeService;

    MergeRequestController(
        MergeRequestService mergeRequestService,
        MergeRequestCollaborationService collaborationService,
        MergeRequestMergeService mergeService
    ) {
        this.mergeRequestService = mergeRequestService;
        this.collaborationService = collaborationService;
        this.mergeService = mergeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<MergeRequestResponse> create(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @Valid @RequestBody CreateMergeRequestRequest request
    ) {
        MergeRequestRecord record = mergeRequestService.create(
            repositoryId,
            user,
            request.title(),
            request.description(),
            request.sourceBranch(),
            request.targetBranch()
        );
        return ApiResponse.success(MergeRequestResponse.from(record), TraceContext.currentTraceId());
    }

    @GetMapping
    ApiResponse<List<MergeRequestResponse>> list(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @RequestParam(required = false) MergeRequestStatus status,
        @RequestParam(required = false) @Size(max = 255) String targetBranch,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        MergeRequestService.MergeRequestPage page = mergeRequestService.list(
            repositoryId,
            user,
            status,
            targetBranch,
            cursor,
            limit
        );
        List<MergeRequestResponse> responses = page.mergeRequests()
            .stream()
            .map(MergeRequestResponse::from)
            .toList();
        return ApiResponse.paged(responses, TraceContext.currentTraceId(), page.nextCursor());
    }

    @GetMapping("/{iid}")
    ApiResponse<MergeRequestResponse> detail(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid
    ) {
        MergeRequestRecord record = mergeRequestService.find(repositoryId, iid, user);
        return ApiResponse.success(MergeRequestResponse.from(record), TraceContext.currentTraceId());
    }

    @PatchMapping("/{iid}")
    ApiResponse<MergeRequestResponse> update(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid,
        @Valid @RequestBody UpdateMergeRequestRequest request
    ) {
        if (request.title() != null && request.title().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Title must not be blank");
        }
        MergeRequestRecord record = mergeRequestService.update(
            repositoryId,
            iid,
            user,
            request.title(),
            request.description(),
            request.status(),
            request.version()
        );
        return ApiResponse.success(MergeRequestResponse.from(record), TraceContext.currentTraceId());
    }

    @PostMapping("/{iid}/merge")
    ApiResponse<MergeRequestMergeService.MergeResult> merge(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid,
        @RequestHeader("Idempotency-Key")
        @Size(min = 8, max = 128)
        @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
        @Valid @RequestBody MergeRequestCommand request
    ) {
        return ApiResponse.success(
            mergeService.merge(
                repositoryId,
                iid,
                user,
                idempotencyKey,
                request.expectedHeadCommit(),
                request.strategy()
            ),
            TraceContext.currentTraceId()
        );
    }

    @GetMapping("/{iid}/diff")
    ApiResponse<MergeRequestDiffService.DiffView> diff(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid
    ) {
        return ApiResponse.success(
            collaborationService.diff(repositoryId, iid, user),
            TraceContext.currentTraceId()
        );
    }

    @PostMapping("/{iid}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<CommentResponse> createComment(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid,
        @Valid @RequestBody CreateCommentRequest request
    ) {
        MergeRequestCollaborationService.CommentPosition position = request.position() == null
            ? null
            : new MergeRequestCollaborationService.CommentPosition(
                request.position().commitId(),
                request.position().filePath(),
                request.position().side(),
                request.position().line()
            );
        MergeRequestCommentRecord comment = collaborationService.createComment(
            repositoryId,
            iid,
            user,
            request.body(),
            position
        );
        return ApiResponse.success(CommentResponse.from(comment), TraceContext.currentTraceId());
    }

    @GetMapping("/{iid}/comments")
    ApiResponse<List<CommentResponse>> comments(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        MergeRequestCollaborationService.CommentPage page = collaborationService.listComments(
            repositoryId,
            iid,
            user,
            cursor,
            limit
        );
        List<CommentResponse> responses = page.comments()
            .stream()
            .map(CommentResponse::from)
            .toList();
        return ApiResponse.paged(responses, TraceContext.currentTraceId(), page.nextCursor());
    }

    record CreateMergeRequestRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 20000) String description,
        @NotBlank @Size(max = 255) String sourceBranch,
        @NotBlank @Size(max = 255) String targetBranch
    ) {
    }

    record UpdateMergeRequestRequest(
        @Size(max = 255) String title,
        @Size(max = 20000) String description,
        MergeRequestStatus status,
        @NotNull @Min(0) Long version
    ) {
        @AssertTrue(message = "at least one mutable field is required")
        boolean isChangePresent() {
            return title != null || description != null || status != null;
        }
    }

    record CreateCommentRequest(
        @NotBlank @Size(max = 10000) String body,
        @Valid CommentPositionRequest position
    ) {
    }

    record MergeRequestCommand(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{40}") String expectedHeadCommit,
        @NotBlank String strategy
    ) {
    }

    record CommentPositionRequest(
        @NotBlank @Size(min = 40, max = 40) String commitId,
        @NotBlank @Size(max = 1024) String filePath,
        @NotNull DiffSide side,
        @NotNull @Min(1) Integer line
    ) {
    }

    record MergeRequestResponse(
        String id,
        int iid,
        String title,
        String description,
        String sourceBranch,
        String targetBranch,
        String baseCommit,
        String headCommit,
        String status,
        AuthorResponse author,
        String mergedBy,
        Instant mergedAt,
        String mergeCommit,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        static MergeRequestResponse from(MergeRequestRecord record) {
            return new MergeRequestResponse(
                Long.toString(record.id()),
                record.iid(),
                record.title(),
                record.description(),
                record.sourceBranch(),
                record.targetBranch(),
                record.baseCommit(),
                record.headCommit(),
                record.status().name(),
                new AuthorResponse(
                    Long.toString(record.authorId()),
                    record.authorUsername(),
                    record.authorDisplayName()
                ),
                record.mergedBy() == null ? null : Long.toString(record.mergedBy()),
                record.mergedAt(),
                record.mergeCommit(),
                record.version(),
                record.createdAt(),
                record.updatedAt()
            );
        }
    }

    record AuthorResponse(String id, String username, String displayName) {
    }

    record CommentResponse(
        String id,
        String type,
        String body,
        AuthorResponse author,
        CommentPositionResponse position,
        Instant createdAt,
        Instant updatedAt
    ) {
        static CommentResponse from(MergeRequestCommentRecord record) {
            AuthorResponse author = record.authorId() == null
                ? null
                : new AuthorResponse(
                    Long.toString(record.authorId()),
                    record.authorUsername(),
                    record.authorDisplayName()
                );
            CommentPositionResponse position = record.type() == MergeRequestCommentType.DIFF
                ? new CommentPositionResponse(
                    record.commitId(),
                    record.filePath(),
                    record.side().name(),
                    record.lineNumber()
                )
                : null;
            return new CommentResponse(
                Long.toString(record.id()),
                record.type().name(),
                record.body(),
                author,
                position,
                record.createdAt(),
                record.updatedAt()
            );
        }
    }

    record CommentPositionResponse(String commitId, String filePath, String side, int line) {
    }
}
