package com.codetrove.repository;

import java.time.Instant;

record RepositoryRecord(
    long id,
    long ownerId,
    String ownerUsername,
    String name,
    String slug,
    String description,
    RepositoryVisibility visibility,
    String defaultBranch,
    String storagePath,
    RepositoryStatus status,
    long version,
    Instant createdAt,
    Instant updatedAt,
    RepositoryRole currentUserRole
) {
}
