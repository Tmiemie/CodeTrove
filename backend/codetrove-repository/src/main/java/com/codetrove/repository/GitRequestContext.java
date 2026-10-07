package com.codetrove.repository;

import com.codetrove.common.security.AuthenticatedUser;

final class GitRequestContext {

    static final String AUTHENTICATED_USER = GitRequestContext.class.getName() + ".user";
    static final String REPOSITORY_RECORD = GitRequestContext.class.getName() + ".repository";

    private GitRequestContext() {
    }

    static AuthenticatedUser authenticatedUser(jakarta.servlet.http.HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AUTHENTICATED_USER);
    }

    static RepositoryRecord repository(jakarta.servlet.http.HttpServletRequest request) {
        return (RepositoryRecord) request.getAttribute(REPOSITORY_RECORD);
    }
}
