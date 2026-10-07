package com.codetrove.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.id.SnowflakeIdGenerator;
import com.codetrove.common.security.AuthenticatedUser;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class RepositoryService {

    private static final String DEFAULT_BRANCH = "main";

    private final RepositoryMetadataRepository metadataRepository;
    private final GitRepositoryStorage gitStorage;
    private final GitRepositoryBrowser gitBrowser;
    private final SnowflakeIdGenerator idGenerator;

    RepositoryService(
        RepositoryMetadataRepository metadataRepository,
        GitRepositoryStorage gitStorage,
        GitRepositoryBrowser gitBrowser,
        SnowflakeIdGenerator idGenerator
    ) {
        this.metadataRepository = metadataRepository;
        this.gitStorage = gitStorage;
        this.gitBrowser = gitBrowser;
        this.idGenerator = idGenerator;
    }

    @Transactional
    RepositoryRecord create(
        AuthenticatedUser owner,
        String name,
        String slug,
        String description,
        RepositoryVisibility visibility,
        boolean initializeWithReadme
    ) {
        Path storagePath = gitStorage.resolveStoragePath(owner.username(), slug);
        long repositoryId = idGenerator.nextId();
        try {
            metadataRepository.create(
                repositoryId,
                owner.id(),
                name.trim(),
                slug,
                normalizeDescription(description),
                visibility,
                DEFAULT_BRANCH,
                storagePath.toString()
            );
            gitStorage.createBareRepository(storagePath, DEFAULT_BRANCH, initializeWithReadme);
            registerRollbackCleanup(storagePath);
            metadataRepository.activate(repositoryId);
            return metadataRepository.findVisibleById(repositoryId, owner.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.REPOSITORY_INITIALIZATION_FAILED));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.REPOSITORY_SLUG_CONFLICT);
        } catch (GitRepositoryStorage.RepositoryStorageException exception) {
            throw new BusinessException(ErrorCode.REPOSITORY_INITIALIZATION_FAILED);
        }
    }

    RepositoryPage listVisible(AuthenticatedUser user, String cursor, int limit) {
        Long beforeId = decodeCursor(cursor);
        List<RepositoryRecord> records = metadataRepository.findVisible(user.id(), beforeId, limit + 1);
        boolean hasNext = records.size() > limit;
        List<RepositoryRecord> page = hasNext ? records.subList(0, limit) : records;
        String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).id()) : null;
        return new RepositoryPage(List.copyOf(page), nextCursor);
    }

    RepositoryRecord findVisible(long repositoryId, AuthenticatedUser user) {
        return metadataRepository.findVisibleById(repositoryId, user.id())
            .orElseThrow(() -> new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND));
    }

    List<GitRepositoryBrowser.BranchView> listBranches(
        long repositoryId,
        AuthenticatedUser user
    ) {
        return gitBrowser.branches(findVisible(repositoryId, user));
    }

    GitRepositoryBrowser.TreePage browseTree(
        long repositoryId,
        AuthenticatedUser user,
        String ref,
        String path,
        String cursor,
        int limit
    ) {
        return gitBrowser.tree(findVisible(repositoryId, user), ref, path, cursor, limit);
    }

    GitRepositoryBrowser.BlobView readBlob(
        long repositoryId,
        AuthenticatedUser user,
        String ref,
        String path
    ) {
        return gitBrowser.blob(findVisible(repositoryId, user), ref, path);
    }

    record RepositoryPage(List<RepositoryRecord> repositories, String nextCursor) {
    }

    private String encodeCursor(long repositoryId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            Long.toString(repositoryId).getBytes(StandardCharsets.UTF_8)
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
            long repositoryId = Long.parseLong(decoded);
            if (repositoryId <= 0) {
                throw new IllegalArgumentException("Cursor repository ID must be positive");
            }
            return repositoryId;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid repository cursor");
        }
    }

    private void registerRollbackCleanup(Path storagePath) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    gitStorage.deleteQuietly(storagePath);
                }
            }
        });
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
