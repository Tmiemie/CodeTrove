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
import com.codetrove.repository.RepositoryRole;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MergeRequestService {

    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestHistoryRepository historyRepository;
    private final RepositoryAccessService repositoryAccessService;
    private final MergeRequestEventService eventService;
    private final SnowflakeIdGenerator idGenerator;

    MergeRequestService(
        MergeRequestRepository mergeRequestRepository,
        MergeRequestHistoryRepository historyRepository,
        RepositoryAccessService repositoryAccessService,
        MergeRequestEventService eventService,
        SnowflakeIdGenerator idGenerator
    ) {
        this.mergeRequestRepository = mergeRequestRepository;
        this.historyRepository = historyRepository;
        this.repositoryAccessService = repositoryAccessService;
        this.eventService = eventService;
        this.idGenerator = idGenerator;
    }

    @Transactional
    MergeRequestRecord create(
        long repositoryId,
        AuthenticatedUser user,
        String title,
        String description,
        String sourceBranch,
        String targetBranch
    ) {
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.require(
            repositoryId,
            user,
            RepositoryPermission.CREATE_MERGE_REQUEST
        );
        String normalizedSource = normalizeBranch(sourceBranch);
        String normalizedTarget = normalizeBranch(targetBranch);
        if (normalizedSource.equals(normalizedTarget)) {
            throw new BusinessException(ErrorCode.MR_BRANCHES_IDENTICAL);
        }
        BranchPair branches = repositoryAccessService.readGit(
            access,
            repository -> resolveBranches(repository, normalizedSource, normalizedTarget)
        );
        if (branches.baseCommit().equals(branches.headCommit())) {
            throw new BusinessException(
                ErrorCode.STATE_CONFLICT,
                "Merge request branches point to the same commit"
            );
        }

        mergeRequestRepository.lockRepository(repositoryId);
        if (mergeRequestRepository.openExists(repositoryId, normalizedSource, normalizedTarget)) {
            throw new BusinessException(ErrorCode.MR_ALREADY_OPEN);
        }
        int iid = mergeRequestRepository.nextIid(repositoryId);
        long mergeRequestId = idGenerator.nextId();
        mergeRequestRepository.create(
            mergeRequestId,
            repositoryId,
            iid,
            title.trim(),
            normalizeDescription(description),
            normalizedSource,
            normalizedTarget,
            branches.baseCommit(),
            branches.headCommit(),
            user.id()
        );
        historyRepository.recordInitial(mergeRequestId, branches.headCommit());
        MergeRequestRecord created = mergeRequestRepository.findByIid(repositoryId, iid)
            .orElseThrow(() -> new IllegalStateException("Created merge request disappeared"));
        eventService.created(created);
        return created;
    }

    MergeRequestPage list(
        long repositoryId,
        AuthenticatedUser user,
        MergeRequestStatus status,
        String targetBranch,
        String cursor,
        int limit
    ) {
        repositoryAccessService.require(repositoryId, user, RepositoryPermission.READ);
        Long beforeId = decodeCursor(cursor);
        String normalizedTarget = normalizeOptionalBranch(targetBranch);
        List<MergeRequestRecord> records = mergeRequestRepository.findPage(
            repositoryId,
            status,
            normalizedTarget,
            beforeId,
            limit + 1
        );
        boolean hasNext = records.size() > limit;
        List<MergeRequestRecord> page = hasNext ? records.subList(0, limit) : records;
        String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).id()) : null;
        return new MergeRequestPage(List.copyOf(page), nextCursor);
    }

    MergeRequestRecord find(long repositoryId, int iid, AuthenticatedUser user) {
        repositoryAccessService.require(repositoryId, user, RepositoryPermission.READ);
        return mergeRequestRepository.findByIid(repositoryId, iid)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
    }

    @Transactional
    MergeRequestRecord update(
        long repositoryId,
        int iid,
        AuthenticatedUser user,
        String title,
        String description,
        MergeRequestStatus requestedStatus,
        long version
    ) {
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.require(
            repositoryId,
            user,
            RepositoryPermission.READ
        );
        MergeRequestRecord current = mergeRequestRepository.findByIid(repositoryId, iid)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
        requireEditor(current, access, user);
        if (current.status() != MergeRequestStatus.OPEN) {
            throw new BusinessException(ErrorCode.MR_NOT_OPEN);
        }
        if (requestedStatus != null && requestedStatus != MergeRequestStatus.CLOSED) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "M1.5 only supports closing an open merge request"
            );
        }
        String nextTitle = title == null ? current.title() : title.trim();
        String nextDescription = description == null
            ? current.description()
            : normalizeDescription(description);
        MergeRequestStatus nextStatus = requestedStatus == null ? current.status() : requestedStatus;
        if (!mergeRequestRepository.updateOpen(
            repositoryId,
            iid,
            nextTitle,
            nextDescription,
            nextStatus,
            version
        )) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        MergeRequestRecord updated = mergeRequestRepository.findByIid(repositoryId, iid)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
        if (nextStatus == MergeRequestStatus.CLOSED) {
            eventService.closed(updated, user.id());
        }
        return updated;
    }

    private BranchPair resolveBranches(
        Repository repository,
        String sourceBranch,
        String targetBranch
    ) throws java.io.IOException {
        ObjectId source = resolveBranch(repository, sourceBranch);
        ObjectId target = resolveBranch(repository, targetBranch);
        return new BranchPair(target.name(), source.name());
    }

    private ObjectId resolveBranch(Repository repository, String branch) throws java.io.IOException {
        String fullRef = Constants.R_HEADS + branch;
        if (!Repository.isValidRefName(fullRef)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid branch name");
        }
        Ref ref = repository.exactRef(fullRef);
        if (ref == null || ref.getObjectId() == null) {
            throw new BusinessException(
                ErrorCode.REPOSITORY_REF_NOT_FOUND,
                "Repository ref not found",
                java.util.Map.of("ref", branch)
            );
        }
        return ref.getObjectId();
    }

    private void requireEditor(
        MergeRequestRecord mergeRequest,
        RepositoryAccessService.RepositoryAccess access,
        AuthenticatedUser user
    ) {
        RepositoryRole role = access.currentUserRole();
        boolean elevated = role == RepositoryRole.OWNER || role == RepositoryRole.MAINTAINER;
        if (mergeRequest.authorId() != user.id() && !elevated) {
            throw new BusinessException(ErrorCode.REPOSITORY_PERMISSION_DENIED);
        }
    }

    private String normalizeBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Branch is required");
        }
        return branch.trim();
    }

    private String normalizeOptionalBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return null;
        }
        return normalizeBranch(branch);
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private String encodeCursor(long mergeRequestId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            Long.toString(mergeRequestId).getBytes(StandardCharsets.UTF_8)
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
            long mergeRequestId = Long.parseLong(decoded);
            if (mergeRequestId <= 0) {
                throw new IllegalArgumentException("Cursor merge request ID must be positive");
            }
            return mergeRequestId;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid merge request cursor");
        }
    }

    record MergeRequestPage(List<MergeRequestRecord> mergeRequests, String nextCursor) {
    }

    private record BranchPair(String baseCommit, String headCommit) {
    }
}
