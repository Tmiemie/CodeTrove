package com.codetrove.auth;

import java.time.Instant;

import com.codetrove.common.security.AuthenticatedUser;

record UserAccount(
    long id,
    String username,
    String passwordHash,
    String displayName,
    UserStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    AuthenticatedUser toAuthenticatedUser() {
        return new AuthenticatedUser(id, username, displayName, status.name());
    }
}
