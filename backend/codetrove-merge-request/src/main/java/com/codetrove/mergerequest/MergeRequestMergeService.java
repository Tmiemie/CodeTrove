package com.codetrove.mergerequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import com.codetrove.check.CheckGateService;
import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.id.SnowflakeIdGenerator;
import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.repository.RepositoryAccessService;
import com.codetrove.repository.RepositoryPermission;
import com.codetrove.repository.RepositoryWriteLockService;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

@Service
class MergeRequestMergeService {

    private final MergeRequestRepository mergeRequestRepository;
    private final MergeOperationRepository operationRepository;
    private final RepositoryAccessService repositoryAccessService;
    private final RepositoryWriteLockService writeLockService;
    private final CheckGateService checkGateService;
    private final SnowflakeIdGenerator idGenerator;

    MergeRequestMergeService(
        MergeRequestRepository mergeRequestRepository,
        MergeOperationRepository operationRepository,
        RepositoryAccessService repositoryAccessService,
        RepositoryWriteLockService writeLockService,
        CheckGateService checkGateService,
        SnowflakeIdGenerator idGenerator
    ) {
        this.mergeRequestRepository = mergeRequestRepository;
        this.operationRepository = operationRepository;
        this.repositoryAccessService = repositoryAccessService;
        this.writeLockService = writeLockService;
        this.checkGateService = checkGateService;
        this.idGenerator = idGenerator;
    }

    MergeResult merge(
        long repositoryId,
        int iid,
        AuthenticatedUser user,
        String idempotencyKey,
        String expectedHeadCommit,
        String strategy
    ) {
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.require(
            repositoryId,
            user,
            RepositoryPermission.MERGE
        );
        if (!"MERGE_COMMIT".equals(strategy)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only MERGE_COMMIT is supported");
        }
        String requestHash = requestHash(iid, expectedHeadCommit, strategy);
        return writeLockService.withLock(repositoryId, () -> mergeLocked(
            access,
            iid,
            user,
            idempotencyKey,
            expectedHeadCommit,
            requestHash
        ));
    }

    private MergeResult mergeLocked(
        RepositoryAccessService.RepositoryAccess access,
        int iid,
        AuthenticatedUser user,
        String idempotencyKey,
        String expectedHeadCommit,
        String requestHash
    ) {
        MergeOperationRepository.MergeOperationRecord existing = operationRepository
            .find(access.id(), idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return replayExisting(access, iid, requestHash, existing);
        }

        MergeRequestRecord mergeRequest = mergeRequestRepository.findByIid(access.id(), iid)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
        if (mergeRequest.status() != MergeRequestStatus.OPEN) {
            throw new BusinessException(ErrorCode.MR_NOT_OPEN);
        }
        if (!mergeRequest.headCommit().equals(expectedHeadCommit)) {
            throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
        }
        checkGateService.requireBlockingChecksPassed(mergeRequest.id(), mergeRequest.headCommit());

        PreparedMerge prepared = repositoryAccessService.writeGit(
            access,
            repository -> prepareMerge(repository, mergeRequest, user, expectedHeadCommit)
        );
        long operationId = idGenerator.nextId();
        operationRepository.createPending(
            operationId,
            access.id(),
            mergeRequest.id(),
            idempotencyKey,
            requestHash,
            expectedHeadCommit,
            prepared.targetBeforeCommit(),
            prepared.mergeCommit(),
            user.id()
        );
        MergeOperationRepository.MergeOperationRecord operation = operationRepository
            .find(access.id(), idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("Pending merge operation was not persisted"));
        try {
            repositoryAccessService.writeGit(access, repository -> {
                updateTargetRef(repository, mergeRequest, prepared);
                return null;
            });
        } catch (RuntimeException exception) {
            operationRepository.deletePending(operationId);
            throw exception;
        }
        MergeRequestRecord merged = operationRepository.complete(
            operation,
            iid,
            mergeRequestRepository
        );
        return MergeResult.from(merged, false);
    }

    private MergeResult replayExisting(
        RepositoryAccessService.RepositoryAccess access,
        int iid,
        String requestHash,
        MergeOperationRepository.MergeOperationRecord operation
    ) {
        if (!operation.requestHash().equals(requestHash)
            || operation.mergeRequestId() != requireMergeRequest(access.id(), iid).id()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        MergeRequestRecord mergeRequest = requireMergeRequest(access.id(), iid);
        if ("SUCCEEDED".equals(operation.status())) {
            return MergeResult.from(mergeRequest, true);
        }
        boolean targetUpdated = repositoryAccessService.readGit(access, repository -> {
            Ref target = repository.exactRef(Constants.R_HEADS + mergeRequest.targetBranch());
            return target != null && target.getObjectId() != null
                && operation.mergeCommit().equals(target.getObjectId().name());
        });
        if (!targetUpdated) {
            throw new BusinessException(
                ErrorCode.STATE_CONFLICT,
                "Pending merge operation has not updated the target branch"
            );
        }
        MergeRequestRecord recovered = operationRepository.complete(
            operation,
            iid,
            mergeRequestRepository
        );
        return MergeResult.from(recovered, true);
    }

    private MergeRequestRecord requireMergeRequest(long repositoryId, int iid) {
        return mergeRequestRepository.findByIid(repositoryId, iid)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
    }

    private PreparedMerge prepareMerge(
        Repository repository,
        MergeRequestRecord mergeRequest,
        AuthenticatedUser user,
        String expectedHead
    ) throws IOException {
        ObjectId source = exactBranch(repository, mergeRequest.sourceBranch());
        ObjectId target = exactBranch(repository, mergeRequest.targetBranch());
        if (!source.name().equals(expectedHead)) {
            throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
        }
        Merger merger = MergeStrategy.RECURSIVE.newMerger(repository, true);
        if (!merger.merge(target, source)) {
            throw new BusinessException(ErrorCode.MR_MERGE_CONFLICT);
        }
        ObjectId resultTree = merger.getResultTreeId();
        if (resultTree == null) {
            throw new BusinessException(ErrorCode.MR_MERGE_CONFLICT);
        }
        try (RevWalk walk = new RevWalk(repository);
             ObjectInserter inserter = repository.newObjectInserter()) {
            RevCommit targetCommit = walk.parseCommit(target);
            RevCommit sourceCommit = walk.parseCommit(source);
            PersonIdent identity = new PersonIdent(
                user.displayName(),
                user.username() + "@users.codetrove.local",
                Instant.now(),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(resultTree);
            commit.setParentIds(targetCommit, sourceCommit);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("Merge !" + mergeRequest.iid() + ": " + mergeRequest.title());
            ObjectId mergeCommit = inserter.insert(commit);
            inserter.flush();
            return new PreparedMerge(target.name(), source.name(), mergeCommit.name());
        }
    }

    private void updateTargetRef(
        Repository repository,
        MergeRequestRecord mergeRequest,
        PreparedMerge prepared
    ) throws IOException {
        ObjectId sourceNow = exactBranch(repository, mergeRequest.sourceBranch());
        if (!prepared.sourceCommit().equals(sourceNow.name())) {
            throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
        }
        RefUpdate update = repository.updateRef(Constants.R_HEADS + mergeRequest.targetBranch());
        update.setExpectedOldObjectId(ObjectId.fromString(prepared.targetBeforeCommit()));
        update.setNewObjectId(ObjectId.fromString(prepared.mergeCommit()));
        update.setRefLogMessage("merge request !" + mergeRequest.iid(), false);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.FORCED) {
            throw new BusinessException(
                ErrorCode.GIT_REF_CHANGED,
                "Target branch changed during merge",
                Map.of("result", result.name())
            );
        }
    }

    private ObjectId exactBranch(Repository repository, String branch) throws IOException {
        Ref ref = repository.exactRef(Constants.R_HEADS + branch);
        if (ref == null || ref.getObjectId() == null) {
            throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
        }
        return ref.getObjectId();
    }

    private String requestHash(int iid, String expectedHead, String strategy) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                (iid + "\n" + expectedHead + "\n" + strategy).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record MergeResult(
        int iid,
        String status,
        String mergeCommit,
        String mergedBy,
        Instant mergedAt,
        boolean idempotentReplay
    ) {
        static MergeResult from(MergeRequestRecord record, boolean replay) {
            return new MergeResult(
                record.iid(),
                record.status().name(),
                record.mergeCommit(),
                record.mergedBy() == null ? null : Long.toString(record.mergedBy()),
                record.mergedAt(),
                replay
            );
        }
    }

    private record PreparedMerge(
        String targetBeforeCommit,
        String sourceCommit,
        String mergeCommit
    ) {
    }
}
