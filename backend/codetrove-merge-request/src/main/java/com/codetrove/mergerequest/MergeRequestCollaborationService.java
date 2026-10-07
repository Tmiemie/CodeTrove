package com.codetrove.mergerequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.id.SnowflakeIdGenerator;
import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.repository.RepositoryAccessService;
import com.codetrove.repository.RepositoryPermission;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MergeRequestCollaborationService {

    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestCommentRepository commentRepository;
    private final MergeRequestDiffService diffService;
    private final RepositoryAccessService repositoryAccessService;
    private final SnowflakeIdGenerator idGenerator;

    MergeRequestCollaborationService(
        MergeRequestRepository mergeRequestRepository,
        MergeRequestCommentRepository commentRepository,
        MergeRequestDiffService diffService,
        RepositoryAccessService repositoryAccessService,
        SnowflakeIdGenerator idGenerator
    ) {
        this.mergeRequestRepository = mergeRequestRepository;
        this.commentRepository = commentRepository;
        this.diffService = diffService;
        this.repositoryAccessService = repositoryAccessService;
        this.idGenerator = idGenerator;
    }

    MergeRequestDiffService.DiffView diff(
        long repositoryId,
        int iid,
        AuthenticatedUser user
    ) {
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.require(
            repositoryId,
            user,
            RepositoryPermission.READ
        );
        MergeRequestRecord mergeRequest = requireMergeRequest(repositoryId, iid);
        return diffService.diff(access, mergeRequest);
    }

    @Transactional
    MergeRequestCommentRecord createComment(
        long repositoryId,
        int iid,
        AuthenticatedUser user,
        String body,
        CommentPosition position
    ) {
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.require(
            repositoryId,
            user,
            RepositoryPermission.COMMENT
        );
        MergeRequestRecord mergeRequest = requireMergeRequest(repositoryId, iid);
        if (mergeRequest.status() != MergeRequestStatus.OPEN) {
            throw new BusinessException(ErrorCode.MR_NOT_OPEN);
        }
        String normalizedBody = body.trim();
        MergeRequestCommentType type = position == null
            ? MergeRequestCommentType.GENERAL
            : MergeRequestCommentType.DIFF;
        if (position != null) {
            validatePosition(access, mergeRequest, position);
        }
        long commentId = idGenerator.nextId();
        commentRepository.create(
            commentId,
            mergeRequest.id(),
            type,
            position == null ? null : position.filePath(),
            position == null ? null : position.side(),
            position == null ? null : position.line(),
            position == null ? null : position.commitId(),
            user.id(),
            normalizedBody
        );
        return commentRepository.findById(commentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
    }

    CommentPage listComments(
        long repositoryId,
        int iid,
        AuthenticatedUser user,
        String cursor,
        int limit
    ) {
        repositoryAccessService.require(repositoryId, user, RepositoryPermission.READ);
        MergeRequestRecord mergeRequest = requireMergeRequest(repositoryId, iid);
        Long afterId = decodeCursor(cursor);
        List<MergeRequestCommentRecord> records = commentRepository.findPage(
            mergeRequest.id(),
            afterId,
            limit + 1
        );
        boolean hasNext = records.size() > limit;
        List<MergeRequestCommentRecord> page = hasNext ? records.subList(0, limit) : records;
        String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).id()) : null;
        return new CommentPage(List.copyOf(page), nextCursor);
    }

    private MergeRequestRecord requireMergeRequest(long repositoryId, int iid) {
        return mergeRequestRepository.findByIid(repositoryId, iid)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
    }

    private void validatePosition(
        RepositoryAccessService.RepositoryAccess access,
        MergeRequestRecord mergeRequest,
        CommentPosition position
    ) {
        if (!mergeRequest.headCommit().equals(position.commitId())
            || !diffService.isValidPosition(
                access,
                mergeRequest,
                position.filePath(),
                position.side(),
                position.line()
            )) {
            throw new BusinessException(ErrorCode.MR_DIFF_POSITION_INVALID);
        }
    }

    private String encodeCursor(long commentId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            Long.toString(commentId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8
            );
            long commentId = Long.parseLong(decoded);
            if (commentId <= 0) {
                throw new IllegalArgumentException("Cursor comment ID must be positive");
            }
            return commentId;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid comment cursor");
        }
    }

    record CommentPosition(String commitId, String filePath, DiffSide side, int line) {
    }

    record CommentPage(List<MergeRequestCommentRecord> comments, String nextCursor) {
    }
}
