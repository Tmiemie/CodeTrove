package com.codetrove.mergerequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.repository.RepositoryAccessService;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MergeRequestDiffService {

    private final RepositoryAccessService repositoryAccessService;
    private final int maxDiffFiles;
    private final int maxFilePatchBytes;
    private final int maxTotalPatchBytes;

    MergeRequestDiffService(
        RepositoryAccessService repositoryAccessService,
        @Value("${codetrove.merge-request.max-diff-files:200}") int maxDiffFiles,
        @Value("${codetrove.merge-request.max-file-patch-bytes:262144}") int maxFilePatchBytes,
        @Value("${codetrove.merge-request.max-total-patch-bytes:2097152}") int maxTotalPatchBytes
    ) {
        if (maxDiffFiles <= 0 || maxFilePatchBytes <= 0 || maxTotalPatchBytes <= 0) {
            throw new IllegalArgumentException("Merge request diff limits must be positive");
        }
        this.repositoryAccessService = repositoryAccessService;
        this.maxDiffFiles = maxDiffFiles;
        this.maxFilePatchBytes = maxFilePatchBytes;
        this.maxTotalPatchBytes = maxTotalPatchBytes;
    }

    DiffView diff(
        RepositoryAccessService.RepositoryAccess access,
        MergeRequestRecord mergeRequest
    ) {
        return analyze(access, mergeRequest).view();
    }

    boolean isValidPosition(
        RepositoryAccessService.RepositoryAccess access,
        MergeRequestRecord mergeRequest,
        String filePath,
        DiffSide side,
        int line
    ) {
        if (line <= 0 || filePath == null || filePath.isBlank()) {
            return false;
        }
        AnalyzedDiff analyzed = analyze(access, mergeRequest);
        return analyzed.positions().stream().anyMatch(position ->
            position.path().equals(filePath)
                && position.side() == side
                && position.ranges().stream().anyMatch(range -> range.contains(line))
        );
    }

    private AnalyzedDiff analyze(
        RepositoryAccessService.RepositoryAccess access,
        MergeRequestRecord mergeRequest
    ) {
        return repositoryAccessService.readGit(
            access,
            repository -> analyzeRepository(repository, mergeRequest)
        );
    }

    private AnalyzedDiff analyzeRepository(
        Repository repository,
        MergeRequestRecord mergeRequest
    ) throws IOException {
        try (RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader();
             ByteArrayOutputStream ignored = new ByteArrayOutputStream();
             DiffFormatter scanner = new DiffFormatter(ignored)) {
            RevCommit base = parseCommit(walk, mergeRequest.baseCommit());
            RevCommit head = parseCommit(walk, mergeRequest.headCommit());
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, base.getTree());
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, head.getTree());
            scanner.setRepository(repository);
            scanner.setDiffComparator(RawTextComparator.DEFAULT);
            scanner.setDetectRenames(true);
            List<DiffEntry> entries = scanner.scan(oldTree, newTree);
            return buildAnalysis(repository, mergeRequest, entries);
        } catch (MissingObjectException | IncorrectObjectTypeException exception) {
            throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
        }
    }

    private AnalyzedDiff buildAnalysis(
        Repository repository,
        MergeRequestRecord mergeRequest,
        List<DiffEntry> entries
    ) throws IOException {
        List<DiffFileView> files = new ArrayList<>();
        List<DiffPosition> positions = new ArrayList<>();
        int totalPatchBytes = 0;
        boolean diffTruncated = entries.size() > maxDiffFiles;
        int fileCount = Math.min(entries.size(), maxDiffFiles);
        for (int index = 0; index < fileCount; index++) {
            DiffEntry entry = entries.get(index);
            FileAnalysis file = analyzeFile(repository, entry, maxTotalPatchBytes - totalPatchBytes);
            files.add(file.view());
            positions.addAll(file.positions());
            totalPatchBytes += file.includedPatchBytes();
            diffTruncated = diffTruncated || file.view().truncated();
        }
        return new AnalyzedDiff(
            new DiffView(
                mergeRequest.baseCommit(),
                mergeRequest.headCommit(),
                List.copyOf(files),
                diffTruncated
            ),
            List.copyOf(positions)
        );
    }

    private FileAnalysis analyzeFile(
        Repository repository,
        DiffEntry entry,
        int remainingTotalBytes
    ) throws IOException {
        FileHeader header;
        try (ByteArrayOutputStream ignored = new ByteArrayOutputStream();
             DiffFormatter formatter = formatter(repository, ignored)) {
            header = formatter.toFileHeader(entry);
        }
        List<Edit> edits = header.toEditList();
        int additions = edits.stream().mapToInt(edit -> edit.getEndB() - edit.getBeginB()).sum();
        int deletions = edits.stream().mapToInt(edit -> edit.getEndA() - edit.getBeginA()).sum();
        boolean binary = header.getPatchType() == FileHeader.PatchType.BINARY;
        String oldPath = pathOrNull(entry.getOldPath());
        String newPath = pathOrNull(entry.getNewPath());
        List<DiffPosition> positions = positions(oldPath, newPath, edits);
        if (binary) {
            return new FileAnalysis(
                new DiffFileView(
                    entry.getChangeType().name(),
                    oldPath,
                    newPath,
                    additions,
                    deletions,
                    true,
                    null,
                    false
                ),
                positions,
                0
            );
        }

        int allowedBytes = Math.min(maxFilePatchBytes, Math.max(0, remainingTotalBytes));
        BoundedOutputStream output = new BoundedOutputStream(allowedBytes);
        try (DiffFormatter formatter = formatter(repository, output)) {
            formatter.format(entry);
            formatter.flush();
        }
        boolean truncated = output.exceeded();
        return new FileAnalysis(
            new DiffFileView(
                entry.getChangeType().name(),
                oldPath,
                newPath,
                additions,
                deletions,
                false,
                new String(output.bytes(), StandardCharsets.UTF_8),
                truncated
            ),
            positions,
            output.bytes().length
        );
    }

    private DiffFormatter formatter(Repository repository, java.io.OutputStream output) {
        DiffFormatter formatter = new DiffFormatter(output);
        formatter.setRepository(repository);
        formatter.setDiffComparator(RawTextComparator.DEFAULT);
        formatter.setDetectRenames(true);
        formatter.setContext(3);
        return formatter;
    }

    private RevCommit parseCommit(RevWalk walk, String commitId) throws IOException {
        try {
            return walk.parseCommit(ObjectId.fromString(commitId));
        } catch (IllegalArgumentException | MissingObjectException | IncorrectObjectTypeException exception) {
            throw new BusinessException(ErrorCode.MR_HEAD_CHANGED);
        }
    }

    private List<DiffPosition> positions(String oldPath, String newPath, List<Edit> edits) {
        List<LineRange> oldRanges = new ArrayList<>();
        List<LineRange> newRanges = new ArrayList<>();
        for (Edit edit : edits) {
            if (edit.getEndA() > edit.getBeginA()) {
                oldRanges.add(new LineRange(edit.getBeginA() + 1, edit.getEndA()));
            }
            if (edit.getEndB() > edit.getBeginB()) {
                newRanges.add(new LineRange(edit.getBeginB() + 1, edit.getEndB()));
            }
        }
        List<DiffPosition> result = new ArrayList<>();
        if (oldPath != null && !oldRanges.isEmpty()) {
            result.add(new DiffPosition(oldPath, DiffSide.OLD, List.copyOf(oldRanges)));
        }
        if (newPath != null && !newRanges.isEmpty()) {
            result.add(new DiffPosition(newPath, DiffSide.NEW, List.copyOf(newRanges)));
        }
        return result;
    }

    private String pathOrNull(String path) {
        return DiffEntry.DEV_NULL.equals(path) ? null : path;
    }

    record DiffView(String baseCommit, String headCommit, List<DiffFileView> files, boolean truncated) {
    }

    record DiffFileView(
        String status,
        String oldPath,
        String newPath,
        int additions,
        int deletions,
        boolean binary,
        String patch,
        boolean truncated
    ) {
    }

    private record AnalyzedDiff(DiffView view, List<DiffPosition> positions) {
    }

    private record FileAnalysis(
        DiffFileView view,
        List<DiffPosition> positions,
        int includedPatchBytes
    ) {
    }

    private record DiffPosition(String path, DiffSide side, List<LineRange> ranges) {
    }

    private record LineRange(int firstLine, int lastLine) {
        boolean contains(int line) {
            return line >= firstLine && line <= lastLine;
        }
    }

    private static final class BoundedOutputStream extends java.io.OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private boolean exceeded;

        private BoundedOutputStream(int limit) {
            this.limit = limit;
            this.delegate = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) {
            if (delegate.size() < limit) {
                delegate.write(value);
            } else {
                exceeded = true;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            int writable = Math.min(length, Math.max(0, limit - delegate.size()));
            delegate.write(bytes, offset, writable);
            if (writable < length) {
                exceeded = true;
            }
        }

        byte[] bytes() {
            return delegate.toByteArray();
        }

        boolean exceeded() {
            return exceeded;
        }
    }
}
