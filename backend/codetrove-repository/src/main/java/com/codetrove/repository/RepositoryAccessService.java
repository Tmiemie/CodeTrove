package com.codetrove.repository;

import java.io.IOException;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.security.AuthenticatedUser;

import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

/** Public read boundary for modules that need authorized access to repository data. */
@Service
public class RepositoryAccessService {

    private final RepositoryMetadataRepository metadataRepository;
    private final RepositoryAuthorizationService authorizationService;
    private final GitRepositoryStorage gitStorage;

    RepositoryAccessService(
        RepositoryMetadataRepository metadataRepository,
        RepositoryAuthorizationService authorizationService,
        GitRepositoryStorage gitStorage
    ) {
        this.metadataRepository = metadataRepository;
        this.authorizationService = authorizationService;
        this.gitStorage = gitStorage;
    }

    public RepositoryAccess require(
        long repositoryId,
        AuthenticatedUser user,
        RepositoryPermission permission
    ) {
        RepositoryRecord record = metadataRepository.findVisibleById(repositoryId, user.id())
            .orElseThrow(() -> new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND));
        if (permission != RepositoryPermission.READ
            && !authorizationService.isAllowed(record.currentUserRole(), permission)) {
            throw new BusinessException(ErrorCode.REPOSITORY_PERMISSION_DENIED);
        }
        return new RepositoryAccess(record);
    }

    public RepositoryAccess requireSystem(long repositoryId) {
        RepositoryRecord record = metadataRepository.findActiveById(repositoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND));
        return new RepositoryAccess(record);
    }

    public <T> T readGit(RepositoryAccess access, GitReadOperation<T> operation) {
        return useGit(access, operation);
    }

    public <T> T writeGit(RepositoryAccess access, GitReadOperation<T> operation) {
        return useGit(access, operation);
    }

    private <T> T useGit(RepositoryAccess access, GitReadOperation<T> operation) {
        try (Repository repository = gitStorage.openExisting(access.record)) {
            return operation.read(repository);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                "Repository storage unavailable"
            );
        }
    }

    @FunctionalInterface
    public interface GitReadOperation<T> {
        T read(Repository repository) throws IOException;
    }

    public static final class RepositoryAccess {
        private final RepositoryRecord record;

        private RepositoryAccess(RepositoryRecord record) {
            this.record = record;
        }

        public long id() {
            return record.id();
        }

        public long ownerId() {
            return record.ownerId();
        }

        public String defaultBranch() {
            return record.defaultBranch();
        }

        public RepositoryRole currentUserRole() {
            return record.currentUserRole();
        }
    }
}
