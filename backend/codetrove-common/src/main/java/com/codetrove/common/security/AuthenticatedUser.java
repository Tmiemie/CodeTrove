package com.codetrove.common.security;

public record AuthenticatedUser(long id, String username, String displayName, String status) {
}
