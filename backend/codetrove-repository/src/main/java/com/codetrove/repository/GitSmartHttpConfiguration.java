package com.codetrove.repository;

import org.eclipse.jgit.http.server.GitServlet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GitSmartHttpConfiguration {

    @Bean
    ServletRegistrationBean<GitServlet> gitServlet(
        RepositoryMetadataRepository metadataRepository,
        RepositoryAuthorizationService authorizationService,
        RepositoryWriteLockService lockService,
        java.util.List<RepositoryRefUpdateListener> updateListeners,
        @Value("${codetrove.git.timeout-seconds:60}") int timeoutSeconds,
        @Value("${codetrove.git.max-command-bytes:1048576}") long maxCommandBytes,
        @Value("${codetrove.git.max-object-bytes:20971520}") long maxObjectBytes,
        @Value("${codetrove.git.max-pack-bytes:104857600}") long maxPackBytes
    ) {
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver(new CodeTroveRepositoryResolver(metadataRepository));
        servlet.setUploadPackFactory(new CodeTroveUploadPackFactory(timeoutSeconds));
        servlet.setReceivePackFactory(new CodeTroveReceivePackFactory(
            authorizationService,
            lockService,
            updateListeners,
            timeoutSeconds,
            maxCommandBytes,
            maxObjectBytes,
            maxPackBytes
        ));
        ServletRegistrationBean<GitServlet> registration = new ServletRegistrationBean<>(
            servlet,
            "/git/*"
        );
        registration.setName("codetroveGitServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<GitBasicAuthenticationFilter>
        gitBasicAuthenticationRegistration(GitBasicAuthenticationFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/git/*");
        registration.setName("codetroveGitBasicAuthenticationFilter");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<GitRequestLimitFilter>
        gitRequestLimitRegistration(GitRequestLimitFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/git/*");
        registration.setName("codetroveGitRequestLimitFilter");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
