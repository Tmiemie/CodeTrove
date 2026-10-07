package com.codetrove.repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class GitRepositoryBrowser {

    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern ABBREVIATED_COMMIT_ID = Pattern.compile("[0-9a-fA-F]{4,39}");
    private static final int BINARY_SAMPLE_BYTES = 8192;

    private final GitRepositoryStorage gitStorage;
    private final int maxInlineBlobBytes;

    GitRepositoryBrowser(
        GitRepositoryStorage gitStorage,
        @Value("${codetrove.repository.max-inline-blob-bytes:1048576}") int maxInlineBlobBytes
    ) {
        if (maxInlineBlobBytes <= 0) {
            throw new IllegalArgumentException("Repository inline blob limit must be positive");
        }
        this.gitStorage = gitStorage;
        this.maxInlineBlobBytes = maxInlineBlobBytes;
    }

    List<BranchView> branches(RepositoryRecord record) {
        try (Repository repository = gitStorage.openExisting(record);
             RevWalk revWalk = new RevWalk(repository)) {
            List<BranchView> branches = new ArrayList<>();
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                ObjectId objectId = ref.getObjectId();
                if (objectId == null) {
                    continue;
                }
                RevCommit commit = revWalk.parseCommit(objectId);
                String name = Repository.shortenRefName(ref.getName());
                branches.add(new BranchView(
                    name,
                    commit.getId().name(),
                    commit.getShortMessage(),
                    commit.getAuthorIdent().getName(),
                    commit.getAuthorIdent().getWhenAsInstant(),
                    record.defaultBranch().equals(name)
                ));
            }
            branches.sort(Comparator.comparing(BranchView::name));
            return List.copyOf(branches);
        } catch (IOException exception) {
            throw storageUnavailable(exception);
        }
    }

    TreePage tree(RepositoryRecord record, String ref, String path, String cursor, int limit) {
        String normalizedPath = normalizePath(path, false);
        String afterName = decodeCursor(cursor);
        try (Repository repository = gitStorage.openExisting(record);
             RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = resolveCommit(repository, revWalk, ref);
            ObjectId treeId = resolveTree(repository, commit, normalizedPath);
            List<TreeEntryView> entries = readTreeEntries(repository, treeId, normalizedPath);
            List<TreeEntryView> remaining = entries.stream()
                .filter(entry -> afterName == null || entry.name().compareTo(afterName) > 0)
                .toList();
            boolean hasNext = remaining.size() > limit;
            List<TreeEntryView> page = hasNext ? remaining.subList(0, limit) : remaining;
            String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).name()) : null;
            return new TreePage(commit.getId().name(), normalizedPath, List.copyOf(page), nextCursor);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageUnavailable(exception);
        }
    }

    BlobView blob(RepositoryRecord record, String ref, String path) {
        String normalizedPath = normalizePath(path, true);
        try (Repository repository = gitStorage.openExisting(record);
             RevWalk revWalk = new RevWalk(repository);
             TreeWalk treeWalk = TreeWalk.forPath(repository, normalizedPath, resolveCommit(
                 repository,
                 revWalk,
                 ref
             ).getTree())) {
            if (treeWalk == null || !isBlobMode(treeWalk.getFileMode(0))) {
                throw pathNotFound(normalizedPath);
            }
            ObjectId objectId = treeWalk.getObjectId(0);
            ObjectLoader loader = repository.open(objectId, Constants.OBJ_BLOB);
            long size = loader.getSize();
            if (size > maxInlineBlobBytes) {
                boolean binary = sampleLooksBinary(loader);
                return new BlobView(
                    normalizedPath,
                    objectId.name(),
                    size,
                    binary,
                    false,
                    null,
                    null,
                    binary ? "BINARY" : "TOO_LARGE"
                );
            }
            byte[] bytes = loader.getCachedBytes(maxInlineBlobBytes);
            String content = decodeUtf8(bytes);
            if (content == null) {
                return new BlobView(
                    normalizedPath,
                    objectId.name(),
                    size,
                    true,
                    false,
                    null,
                    null,
                    "BINARY"
                );
            }
            return new BlobView(
                normalizedPath,
                objectId.name(),
                size,
                false,
                true,
                content,
                "UTF-8",
                null
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageUnavailable(exception);
        }
    }

    private ObjectId resolveTree(Repository repository, RevCommit commit, String path) throws IOException {
        if (path.isEmpty()) {
            return commit.getTree().getId();
        }
        try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (treeWalk == null || !FileMode.TREE.equals(treeWalk.getFileMode(0))) {
                throw pathNotFound(path);
            }
            return treeWalk.getObjectId(0);
        }
    }

    private List<TreeEntryView> readTreeEntries(
        Repository repository,
        ObjectId treeId,
        String parentPath
    ) throws IOException {
        List<TreeEntryView> entries = new ArrayList<>();
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(treeId);
            treeWalk.setRecursive(false);
            while (treeWalk.next()) {
                FileMode mode = treeWalk.getFileMode(0);
                String name = treeWalk.getNameString();
                String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
                ObjectId objectId = treeWalk.getObjectId(0);
                entries.add(new TreeEntryView(
                    name,
                    path,
                    entryType(mode),
                    objectId.name(),
                    isBlobMode(mode) ? repository.open(objectId, Constants.OBJ_BLOB).getSize() : null
                ));
            }
        }
        entries.sort(Comparator.comparing(TreeEntryView::name));
        return entries;
    }

    private RevCommit resolveCommit(Repository repository, RevWalk revWalk, String ref) throws IOException {
        if (ref == null || ref.isBlank() || ref.length() > 255) {
            throw validation("Invalid repository ref");
        }
        String requested = ref.trim();
        ObjectId objectId;
        if (COMMIT_ID.matcher(requested).matches()) {
            objectId = ObjectId.fromString(requested.toLowerCase(Locale.ROOT));
        } else {
            if (ABBREVIATED_COMMIT_ID.matcher(requested).matches()
                || requested.startsWith(Constants.R_REFS) && !requested.startsWith(Constants.R_HEADS)) {
                throw validation("Invalid repository ref");
            }
            String branch = requested.startsWith(Constants.R_HEADS)
                ? requested.substring(Constants.R_HEADS.length())
                : requested;
            String fullRef = Constants.R_HEADS + branch;
            if (branch.isBlank() || !Repository.isValidRefName(fullRef)) {
                throw validation("Invalid repository ref");
            }
            Ref exactRef = repository.exactRef(fullRef);
            if (exactRef == null || exactRef.getObjectId() == null) {
                throw refNotFound(requested);
            }
            objectId = exactRef.getObjectId();
        }
        try {
            return revWalk.parseCommit(objectId);
        } catch (MissingObjectException | IncorrectObjectTypeException exception) {
            throw refNotFound(requested);
        }
    }

    private String normalizePath(String path, boolean required) {
        if (path == null || path.isEmpty()) {
            if (required) {
                throw validation("Repository path is required");
            }
            return "";
        }
        if (path.length() > 1024 || path.startsWith("/") || path.endsWith("/")
            || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw validation("Invalid repository path");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw validation("Invalid repository path");
            }
        }
        return String.join("/", segments);
    }

    private String encodeCursor(String entryName) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            entryName.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            String decoded = decodeUtf8(bytes);
            if (decoded == null || decoded.isEmpty() || decoded.indexOf('/') >= 0) {
                throw new IllegalArgumentException("Invalid tree cursor");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw validation("Invalid tree cursor");
        }
    }

    private boolean sampleLooksBinary(ObjectLoader loader) throws IOException {
        int sampleSize = (int) Math.min(loader.getSize(), BINARY_SAMPLE_BYTES);
        byte[] sample = new byte[sampleSize];
        int offset = 0;
        try (InputStream input = loader.openStream()) {
            while (offset < sample.length) {
                int read = input.read(sample, offset, sample.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        for (int index = 0; index < offset; index++) {
            if (sample[index] == 0) {
                return true;
            }
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer output = CharBuffer.allocate(Math.max(1, offset));
        return decoder.decode(ByteBuffer.wrap(sample, 0, offset), output, false).isError();
    }

    private String decodeUtf8(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private String entryType(FileMode mode) {
        if (FileMode.TREE.equals(mode)) {
            return "TREE";
        }
        if (FileMode.GITLINK.equals(mode)) {
            return "GITLINK";
        }
        if (FileMode.SYMLINK.equals(mode)) {
            return "SYMLINK";
        }
        return "BLOB";
    }

    private boolean isBlobMode(FileMode mode) {
        return FileMode.REGULAR_FILE.equals(mode)
            || FileMode.EXECUTABLE_FILE.equals(mode)
            || FileMode.SYMLINK.equals(mode);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private BusinessException refNotFound(String ref) {
        return new BusinessException(
            ErrorCode.REPOSITORY_REF_NOT_FOUND,
            "Repository ref not found",
            java.util.Map.of("ref", ref)
        );
    }

    private BusinessException pathNotFound(String path) {
        return new BusinessException(
            ErrorCode.REPOSITORY_PATH_NOT_FOUND,
            "Repository path not found",
            java.util.Map.of("path", path)
        );
    }

    private BusinessException storageUnavailable(IOException exception) {
        return new BusinessException(
            ErrorCode.DEPENDENCY_UNAVAILABLE,
            "Repository storage unavailable"
        );
    }

    record BranchView(
        String name,
        String commitId,
        String commitMessage,
        String authorName,
        Instant authoredAt,
        @JsonProperty("default") boolean defaultBranch
    ) {
    }

    record TreeEntryView(String name, String path, String type, String objectId, Long size) {
    }

    record TreePage(String commitId, String path, List<TreeEntryView> entries, String nextCursor) {
    }

    record BlobView(
        String path,
        String objectId,
        long size,
        boolean binary,
        boolean contentIncluded,
        String content,
        String encoding,
        String notIncludedReason
    ) {
    }
}
