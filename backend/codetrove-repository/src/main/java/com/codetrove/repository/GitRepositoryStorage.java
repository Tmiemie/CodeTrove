package com.codetrove.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class GitRepositoryStorage {

    private final Path storageRoot;

    GitRepositoryStorage(@Value("${codetrove.repository.storage-root}") String storageRoot) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    Path resolveStoragePath(String ownerUsername, String slug) {
        Path resolved = storageRoot.resolve(ownerUsername).resolve(slug + ".git").normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Repository path escapes configured storage root");
        }
        return resolved;
    }

    Repository openExisting(RepositoryRecord record) throws IOException {
        Path expected = resolveStoragePath(record.ownerUsername(), record.slug());
        Path stored = Path.of(record.storagePath()).toAbsolutePath().normalize();
        Path realRoot = storageRoot.toRealPath();
        Path realStored = stored.toRealPath();
        if (!stored.equals(expected) || !realStored.startsWith(realRoot)
            || Files.isSymbolicLink(stored) || Files.isSymbolicLink(stored.getParent())) {
            throw new IOException("Repository storage path is outside the controlled root");
        }
        return new FileRepositoryBuilder()
            .setGitDir(realStored.toFile())
            .setMustExist(true)
            .build();
    }

    void createBareRepository(Path barePath, String defaultBranch, boolean initializeWithReadme) {
        if (Files.exists(barePath)) {
            throw new RepositoryStorageException(
                "Repository storage path already exists",
                new IOException("Refusing to reuse existing repository directory")
            );
        }
        try {
            Files.createDirectories(barePath.getParent());
            try (Git bareGit = Git.init()
                .setBare(true)
                .setDirectory(barePath.toFile())
                .setInitialBranch(defaultBranch)
                .call()) {
                bareGit.getRepository().getConfig().setBoolean("http", null, "receivepack", true);
                bareGit.getRepository().getConfig().save();
            }
            if (initializeWithReadme) {
                createInitialReadmeCommit(barePath, defaultBranch);
            }
        } catch (IOException | GitAPIException exception) {
            deleteQuietly(barePath);
            throw new RepositoryStorageException("Failed to initialize Git repository", exception);
        }
    }

    void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    current.toFile().setWritable(true);
                    Files.deleteIfExists(current);
                } catch (IOException ignored) {
                    // Best-effort compensation; the original failure remains authoritative.
                }
            });
        } catch (IOException ignored) {
            // Best-effort compensation; the original failure remains authoritative.
        }
    }

    private void createInitialReadmeCommit(Path barePath, String defaultBranch) throws IOException {
        try (Repository repository = new FileRepositoryBuilder().setGitDir(barePath.toFile()).build();
             ObjectInserter inserter = repository.newObjectInserter()) {
            byte[] readme = "# CodeTrove Repository\n".getBytes(StandardCharsets.UTF_8);
            ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, readme);

            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            DirCacheEntry entry = new DirCacheEntry("README.md");
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(blobId);
            builder.add(entry);
            builder.finish();
            ObjectId treeId = cache.writeTree(inserter);

            PersonIdent author = new PersonIdent(
                "CodeTrove",
                "noreply@codetrove.local",
                Instant.now(),
                ZoneOffset.UTC
            );
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage("chore: initialize repository");
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate branch = repository.updateRef("refs/heads/" + defaultBranch);
            branch.setNewObjectId(commitId);
            branch.setRefLogMessage("initialize repository", false);
            RefUpdate.Result result = branch.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
                throw new IOException("Unable to update initial branch: " + result);
            }
            repository.updateRef(Constants.HEAD, true).link("refs/heads/" + defaultBranch);
        }
    }

    static final class RepositoryStorageException extends RuntimeException {
        RepositoryStorageException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
