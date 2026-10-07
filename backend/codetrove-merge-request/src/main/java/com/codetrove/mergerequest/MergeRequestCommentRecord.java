package com.codetrove.mergerequest;

import java.time.Instant;

record MergeRequestCommentRecord(
    long id,
    long mergeRequestId,
    MergeRequestCommentType type,
    String filePath,
    DiffSide side,
    Integer lineNumber,
    String commitId,
    Long authorId,
    String authorUsername,
    String authorDisplayName,
    String authorType,
    String body,
    Instant createdAt,
    Instant updatedAt
) {
}
