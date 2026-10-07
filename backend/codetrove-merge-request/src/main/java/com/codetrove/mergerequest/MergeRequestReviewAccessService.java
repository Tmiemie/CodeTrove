package com.codetrove.mergerequest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.id.SnowflakeIdGenerator;
import com.codetrove.repository.RepositoryAccessService;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

/** Public boundary used by trusted background reviewers. */
@Service
public class MergeRequestReviewAccessService {

    private static final int MAX_ASSAY_FILES = 50;
    private static final int MAX_ASSAY_FILE_BYTES = 262_144;
    private static final int MAX_ASSAY_TOTAL_BYTES = 2_097_152;

    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestDiffService diffService;
    private final MergeRequestCommentRepository commentRepository;
    private final RepositoryAccessService repositoryAccessService;
    private final SnowflakeIdGenerator idGenerator;

    MergeRequestReviewAccessService(
        MergeRequestRepository mergeRequestRepository,
        MergeRequestDiffService diffService,
        MergeRequestCommentRepository commentRepository,
        RepositoryAccessService repositoryAccessService,
        SnowflakeIdGenerator idGenerator
    ) {
        this.mergeRequestRepository = mergeRequestRepository;
        this.diffService = diffService;
        this.commentRepository = commentRepository;
        this.repositoryAccessService = repositoryAccessService;
        this.idGenerator = idGenerator;
    }

    public long requireMergeRequestId(long repositoryId, int iid) {
        return mergeRequestRepository.findByIid(repositoryId, iid)
            .map(MergeRequestRecord::id)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
    }

    public ReviewDiff requireCurrentDiff(long repositoryId, long mergeRequestId, String headCommit) {
        MergeRequestRecord mergeRequest = mergeRequestRepository.findById(mergeRequestId)
            .filter(record -> record.repositoryId() == repositoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
        if (mergeRequest.status() != MergeRequestStatus.OPEN || !mergeRequest.headCommit().equals(headCommit)) {
            throw new BusinessException(ErrorCode.CHECK_STALE);
        }
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.requireSystem(repositoryId);
        MergeRequestDiffService.DiffView diff = diffService.diff(access, mergeRequest);
        List<ReviewDiffFile> files = diff.files().stream().map(file -> new ReviewDiffFile(
            file.status(),
            file.oldPath(),
            file.newPath(),
            file.binary(),
            file.patch(),
            file.truncated()
        )).toList();
        return new ReviewDiff(
            mergeRequest.id(),
            mergeRequest.iid(),
            diff.baseCommit(),
            diff.headCommit(),
            files,
            diff.truncated()
        );
    }

    public boolean createSystemReviewComment(
        long repositoryId,
        long mergeRequestId,
        String headCommit,
        String filePath,
        int lineNumber,
        String body,
        String fingerprint
    ) {
        MergeRequestRecord mergeRequest = mergeRequestRepository.findById(mergeRequestId)
            .filter(record -> record.repositoryId() == repositoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
        if (mergeRequest.status() != MergeRequestStatus.OPEN || !mergeRequest.headCommit().equals(headCommit)) {
            throw new BusinessException(ErrorCode.CHECK_STALE);
        }
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.requireSystem(repositoryId);
        if (!diffService.isValidPosition(access, mergeRequest, filePath, DiffSide.NEW, lineNumber)) {
            throw new BusinessException(ErrorCode.MR_DIFF_POSITION_INVALID);
        }
        return commentRepository.createSystemReview(
            idGenerator.nextId(),
            mergeRequestId,
            filePath,
            DiffSide.NEW,
            lineNumber,
            headCommit,
            body,
            fingerprint
        );
    }

    public List<TestCaseSource> requireCurrentTestCases(
        long repositoryId,
        long mergeRequestId,
        String headCommit
    ) {
        MergeRequestRecord mergeRequest = requireCurrent(repositoryId, mergeRequestId, headCommit);
        RepositoryAccessService.RepositoryAccess access = repositoryAccessService.requireSystem(repositoryId);
        return repositoryAccessService.readGit(access, repository -> {
            ObjectId commitId = repository.resolve(headCommit);
            if (commitId == null) {
                throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
            }
            List<TestCaseSource> sources = new ArrayList<>();
            int[] totalBytes = new int[] {0};
            try (RevWalk revWalk = new RevWalk(repository);
                 TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(revWalk.parseCommit(commitId).getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (!isTestCasePath(path) || !FileMode.REGULAR_FILE.equals(treeWalk.getFileMode(0))) {
                        continue;
                    }
                    if (sources.size() >= MAX_ASSAY_FILES) {
                        throw new BusinessException(
                            ErrorCode.VALIDATION_FAILED,
                            "Too many assay test case files"
                        );
                    }
                    var loader = repository.open(treeWalk.getObjectId(0));
                    if (loader.getSize() > MAX_ASSAY_FILE_BYTES) {
                        throw new BusinessException(
                            ErrorCode.VALIDATION_FAILED,
                            "Assay test case file is too large"
                        );
                    }
                    byte[] bytes = loader.getBytes(MAX_ASSAY_FILE_BYTES);
                    totalBytes[0] += bytes.length;
                    if (totalBytes[0] > MAX_ASSAY_TOTAL_BYTES) {
                        throw new BusinessException(
                            ErrorCode.VALIDATION_FAILED,
                            "Assay test case payload is too large"
                        );
                    }
                    sources.add(new TestCaseSource(path, strictUtf8(bytes)));
                }
            }
            return sources.stream().sorted(Comparator.comparing(TestCaseSource::path)).toList();
        });
    }

    public boolean createSystemTestReport(
        long repositoryId,
        long mergeRequestId,
        String headCommit,
        String body,
        String fingerprint
    ) {
        requireCurrent(repositoryId, mergeRequestId, headCommit);
        return commentRepository.createSystemTestReport(
            idGenerator.nextId(),
            mergeRequestId,
            body,
            fingerprint
        );
    }

    private MergeRequestRecord requireCurrent(
        long repositoryId,
        long mergeRequestId,
        String headCommit
    ) {
        MergeRequestRecord mergeRequest = mergeRequestRepository.findById(mergeRequestId)
            .filter(record -> record.repositoryId() == repositoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND));
        if (mergeRequest.status() != MergeRequestStatus.OPEN || !mergeRequest.headCommit().equals(headCommit)) {
            throw new BusinessException(ErrorCode.CHECK_STALE);
        }
        return mergeRequest;
    }

    private boolean isTestCasePath(String path) {
        return path.startsWith("testcases/") && path.endsWith(".json") && !path.contains("\\");
    }

    private String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Assay test case must be valid UTF-8"
            );
        }
    }

    public record TestCaseSource(String path, String content) {
    }

    public record ReviewDiff(
        long mergeRequestId,
        int mergeRequestIid,
        String baseCommit,
        String headCommit,
        List<ReviewDiffFile> files,
        boolean truncated
    ) {
    }

    public record ReviewDiffFile(
        String status,
        String oldPath,
        String newPath,
        boolean binary,
        String patch,
        boolean truncated
    ) {
    }
}
