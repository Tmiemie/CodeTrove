package com.codetrove.repository;

import java.util.Collection;

import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

class ProtectedBranchHook implements PreReceiveHook {

    private final String protectedRef;

    ProtectedBranchHook(String defaultBranch) {
        this.protectedRef = "refs/heads/" + defaultBranch;
    }

    @Override
    public void onPreReceive(ReceivePack receivePack, Collection<ReceiveCommand> commands) {
        boolean touchesProtectedBranch = commands.stream()
            .anyMatch(command -> protectedRef.equals(command.getRefName()));
        if (!touchesProtectedBranch) {
            return;
        }
        for (ReceiveCommand command : commands) {
            if (command.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "protected branch requires merge request"
                );
            }
        }
    }
}
