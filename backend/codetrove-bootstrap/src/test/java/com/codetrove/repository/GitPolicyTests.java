package com.codetrove.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.codetrove.common.security.AuthenticatedUser;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

class GitPolicyTests {

    @TempDir
    private Path tempDirectory;

    @Test
    void protectedBranchRejectsEveryCommandInTheSamePush() {
        ReceiveCommand main = command("refs/heads/main");
        ReceiveCommand feature = command("refs/heads/feature/test");

        new ProtectedBranchHook("main").onPreReceive(null, List.of(main, feature));

        assertThat(main.getResult()).isEqualTo(ReceiveCommand.Result.REJECTED_OTHER_REASON);
        assertThat(main.getMessage()).isEqualTo("protected branch requires merge request");
        assertThat(feature.getResult()).isEqualTo(ReceiveCommand.Result.REJECTED_OTHER_REASON);
        assertThat(feature.getMessage()).isEqualTo("protected branch requires merge request");
    }

    @Test
    void unprotectedBranchRemainsEligibleForReceivePack() {
        ReceiveCommand feature = command("refs/heads/feature/test");

        new ProtectedBranchHook("main").onPreReceive(null, List.of(feature));

        assertThat(feature.getResult()).isEqualTo(ReceiveCommand.Result.NOT_ATTEMPTED);
    }

    @Test
    void ownerReceivePackAppliesAllSafetyLimits() throws Exception {
        MockHttpServletRequest request = requestFor(RepositoryRole.OWNER);
        Path gitDirectory = tempDirectory.resolve("owner.git");
        try (var repository = FileRepositoryBuilder.create(gitDirectory.toFile())) {
            var factory = new CodeTroveReceivePackFactory(
                new RepositoryAuthorizationService(),
                new RepositoryWriteLockService(),
                List.of(),
                5,
                1024,
                2048,
                4096
            );

            var receivePack = factory.create(request, repository);

            assertThat(receivePack.getTimeout()).isEqualTo(5);
            assertThat(receivePack.isAllowCreates()).isTrue();
            assertThat(receivePack.isAllowDeletes()).isTrue();
            assertThat(receivePack.isAllowNonFastForwards()).isFalse();
            assertThat(receivePack.getPreReceiveHook()).isNotNull();
            assertThat(receivePack.getPostReceiveHook()).isNotNull();
            assertThat(ReflectionTestUtils.getField(receivePack, "maxCommandBytes")).isEqualTo(1024L);
            assertThat(ReflectionTestUtils.getField(receivePack, "maxObjectSizeLimit")).isEqualTo(2048L);
            assertThat(ReflectionTestUtils.getField(receivePack, "maxPackSizeLimit")).isEqualTo(4096L);
        }
    }

    @Test
    void reporterCannotCreateReceivePack() throws Exception {
        MockHttpServletRequest request = requestFor(RepositoryRole.REPORTER);
        Path gitDirectory = tempDirectory.resolve("reporter.git");
        try (var repository = FileRepositoryBuilder.create(gitDirectory.toFile())) {
            var factory = new CodeTroveReceivePackFactory(
                new RepositoryAuthorizationService(),
                new RepositoryWriteLockService(),
                List.of(),
                5,
                1024,
                2048,
                4096
            );

            assertThatThrownBy(() -> factory.create(request, repository))
                .isInstanceOf(ServiceNotAuthorizedException.class);
        }
    }

    @Test
    void uploadPackUsesConfiguredTimeout() throws Exception {
        Path gitDirectory = tempDirectory.resolve("upload.git");
        try (var repository = FileRepositoryBuilder.create(gitDirectory.toFile())) {
            var uploadPack = new CodeTroveUploadPackFactory(5).create(
                new MockHttpServletRequest(),
                repository
            );

            assertThat(uploadPack.getTimeout()).isEqualTo(5);
        }
    }

    private MockHttpServletRequest requestFor(RepositoryRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
            GitRequestContext.AUTHENTICATED_USER,
            new AuthenticatedUser(10L, "git-user", "Git User", "ACTIVE")
        );
        request.setAttribute(
            GitRequestContext.REPOSITORY_RECORD,
            new RepositoryRecord(
                20L,
                10L,
                "git-user",
                "Git Repository",
                "git-repository",
                "Git policy test",
                RepositoryVisibility.PRIVATE,
                "main",
                tempDirectory.resolve("git-user/git-repository.git").toString(),
                RepositoryStatus.ACTIVE,
                0L,
                Instant.now(),
                Instant.now(),
                role
            )
        );
        return request;
    }

    private ReceiveCommand command(String refName) {
        return new ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("1111111111111111111111111111111111111111"),
            refName
        );
    }
}
