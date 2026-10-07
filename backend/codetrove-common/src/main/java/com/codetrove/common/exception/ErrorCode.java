package com.codetrove.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Authentication token is invalid"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Authentication token has expired"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    USERNAME_CONFLICT(HttpStatus.CONFLICT, "Username already exists"),
    REPOSITORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Repository not found"),
    REPOSITORY_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "Repository permission denied"),
    REPOSITORY_SLUG_CONFLICT(HttpStatus.CONFLICT, "Repository slug already exists"),
    REPOSITORY_REF_NOT_FOUND(HttpStatus.NOT_FOUND, "Repository ref not found"),
    REPOSITORY_PATH_NOT_FOUND(HttpStatus.NOT_FOUND, "Repository path not found"),
    REPOSITORY_INITIALIZATION_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Repository initialization failed"
    ),
    MERGE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "Merge request not found"),
    MR_NOT_OPEN(HttpStatus.CONFLICT, "Merge request is not open"),
    MR_ALREADY_OPEN(HttpStatus.CONFLICT, "An open merge request already exists"),
    MR_BRANCHES_IDENTICAL(HttpStatus.CONFLICT, "Merge request branches must differ"),
    MR_HEAD_CHANGED(HttpStatus.CONFLICT, "Merge request head is no longer available"),
    MR_DIFF_POSITION_INVALID(HttpStatus.BAD_REQUEST, "Merge request diff position is invalid"),
    MR_MERGE_CONFLICT(HttpStatus.CONFLICT, "Merge request has conflicts"),
    GIT_REF_CHANGED(HttpStatus.CONFLICT, "Git reference changed during the operation"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key was used for another request"),
    MR_CHECKS_NOT_PASSED(HttpStatus.CONFLICT, "Blocking checks have not passed"),
    CHECK_STALE(HttpStatus.CONFLICT, "Check result is stale"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "Resource state conflict"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
