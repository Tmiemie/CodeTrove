package com.codetrove.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;

final class RepositoryReceiveHooks {

    private RepositoryReceiveHooks() {
    }

    static HookPair hooks(
        long repositoryId,
        String defaultBranch,
        RepositoryWriteLockService lockService,
        List<RepositoryRefUpdateListener> listeners
    ) {
        ProtectedBranchHook protectedBranchHook = new ProtectedBranchHook(defaultBranch);
        AtomicReference<RepositoryWriteLockService.Lease> lease = new AtomicReference<>();
        PreReceiveHook pre = (receivePack, commands) -> {
            lease.set(lockService.acquire(repositoryId));
            protectedBranchHook.onPreReceive(receivePack, commands);
        };
        PostReceiveHook post = (receivePack, commands) -> {
            try {
                List<RepositoryRefUpdate> updates = successfulUpdates(commands);
                if (!updates.isEmpty()) {
                    for (RepositoryRefUpdateListener listener : listeners) {
                        listener.afterRefsUpdated(repositoryId, updates);
                    }
                }
            } finally {
                RepositoryWriteLockService.Lease acquired = lease.getAndSet(null);
                if (acquired != null) {
                    acquired.close();
                }
            }
        };
        return new HookPair(pre, post);
    }

    private static List<RepositoryRefUpdate> successfulUpdates(Collection<ReceiveCommand> commands) {
        List<RepositoryRefUpdate> updates = new ArrayList<>();
        for (ReceiveCommand command : commands) {
            if (command.getResult() != ReceiveCommand.Result.OK) {
                continue;
            }
            ObjectId oldId = command.getOldId();
            ObjectId newId = command.getNewId();
            updates.add(new RepositoryRefUpdate(
                command.getRefName(),
                oldId == null || ObjectId.zeroId().equals(oldId) ? null : oldId.name(),
                newId == null || ObjectId.zeroId().equals(newId) ? null : newId.name(),
                newId == null || ObjectId.zeroId().equals(newId)
            ));
        }
        return List.copyOf(updates);
    }

    record HookPair(PreReceiveHook preReceive, PostReceiveHook postReceive) {
    }
}
