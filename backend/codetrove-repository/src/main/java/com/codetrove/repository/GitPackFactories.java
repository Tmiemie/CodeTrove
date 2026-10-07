package com.codetrove.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.codetrove.common.security.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

class CodeTroveUploadPackFactory implements UploadPackFactory<HttpServletRequest> {

    private final int timeoutSeconds;

    CodeTroveUploadPackFactory(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public UploadPack create(HttpServletRequest request, Repository repository) {
        UploadPack uploadPack = new UploadPack(repository);
        uploadPack.setTimeout(timeoutSeconds);
        return uploadPack;
    }
}

class CodeTroveReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    private final RepositoryAuthorizationService authorizationService;
    private final RepositoryWriteLockService lockService;
    private final List<RepositoryRefUpdateListener> updateListeners;
    private final int timeoutSeconds;
    private final long maxCommandBytes;
    private final long maxObjectBytes;
    private final long maxPackBytes;

    CodeTroveReceivePackFactory(
        RepositoryAuthorizationService authorizationService,
        RepositoryWriteLockService lockService,
        List<RepositoryRefUpdateListener> updateListeners,
        int timeoutSeconds,
        long maxCommandBytes,
        long maxObjectBytes,
        long maxPackBytes
    ) {
        this.authorizationService = authorizationService;
        this.lockService = lockService;
        this.updateListeners = List.copyOf(updateListeners);
        this.timeoutSeconds = timeoutSeconds;
        this.maxCommandBytes = maxCommandBytes;
        this.maxObjectBytes = maxObjectBytes;
        this.maxPackBytes = maxPackBytes;
    }

    @Override
    public ReceivePack create(HttpServletRequest request, Repository repository)
        throws ServiceNotAuthorizedException {
        AuthenticatedUser user = GitRequestContext.authenticatedUser(request);
        RepositoryRecord record = GitRequestContext.repository(request);
        if (user == null || record == null || record.currentUserRole() == null
            || !authorizationService.isAllowed(record.currentUserRole(), RepositoryPermission.PUSH)) {
            throw new ServiceNotAuthorizedException();
        }
        ReceivePack receivePack = new ReceivePack(repository);
        receivePack.setTimeout(timeoutSeconds);
        receivePack.setAllowCreates(true);
        receivePack.setAllowDeletes(true);
        receivePack.setAllowNonFastForwards(false);
        receivePack.setMaxCommandBytes(maxCommandBytes);
        receivePack.setMaxObjectSizeLimit(maxObjectBytes);
        receivePack.setMaxPackSizeLimit(maxPackBytes);
        receivePack.setRefLogIdent(new PersonIdent(
            user.username(),
            user.username() + "@users.codetrove.local",
            Instant.now(),
            ZoneOffset.UTC
        ));
        var hooks = RepositoryReceiveHooks.hooks(
            record.id(),
            record.defaultBranch(),
            lockService,
            updateListeners
        );
        receivePack.setPreReceiveHook(hooks.preReceive());
        receivePack.setPostReceiveHook(hooks.postReceive());
        return receivePack;
    }
}
