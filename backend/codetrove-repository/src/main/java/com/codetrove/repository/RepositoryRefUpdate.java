package com.codetrove.repository;

/** A successfully applied Git reference update. */
public record RepositoryRefUpdate(
    String refName,
    String oldObjectId,
    String newObjectId,
    boolean deletion
) {
}
