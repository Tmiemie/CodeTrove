package com.codetrove.repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codetrove.common.security.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;

class CodeTroveRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    private static final Pattern REPOSITORY_NAME = Pattern.compile(
        "([a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)/([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\\.git"
    );

    private final RepositoryMetadataRepository metadataRepository;

    CodeTroveRepositoryResolver(RepositoryMetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    @Override
    public Repository open(HttpServletRequest request, String name) throws RepositoryNotFoundException {
        AuthenticatedUser user = GitRequestContext.authenticatedUser(request);
        Matcher matcher = REPOSITORY_NAME.matcher(name);
        if (user == null || !matcher.matches()) {
            throw new RepositoryNotFoundException(name);
        }
        RepositoryRecord repository = metadataRepository.findActiveByOwnerAndSlug(
            matcher.group(1),
            matcher.group(2),
            user.id()
        ).filter(record -> record.visibility() == RepositoryVisibility.PUBLIC
            || record.currentUserRole() != null)
            .orElseThrow(() -> new RepositoryNotFoundException(name));
        try {
            Repository opened = new FileRepositoryBuilder()
                .setGitDir(Path.of(repository.storagePath()).toFile())
                .setMustExist(true)
                .build();
            request.setAttribute(GitRequestContext.REPOSITORY_RECORD, repository);
            return opened;
        } catch (IOException exception) {
            throw new RepositoryNotFoundException(name);
        }
    }
}
