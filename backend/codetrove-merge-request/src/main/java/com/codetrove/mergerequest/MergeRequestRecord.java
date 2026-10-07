package com.codetrove.mergerequest;

import java.time.Instant;

record MergeRequestRecord(
    long id,
    long repositoryId,
    int iid,
    String title,
    String description,
    String sourceBranch,
    String targetBranch,
    String baseCommit,
    String headCommit,
    MergeRequestStatus status,
    long authorId,
    String authorUsername,
    String authorDisplayName,
    Long mergedBy,
    Instant mergedAt,
    String mergeCommit,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
}
