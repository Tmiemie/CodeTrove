package com.codetrove.repository;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class GitRequestLimitFilter extends OncePerRequestFilter {

    private final long maxRequestBytes;
    private final Semaphore permits;

    GitRequestLimitFilter(
        @Value("${codetrove.git.max-request-bytes:115343360}") long maxRequestBytes,
        @Value("${codetrove.git.max-concurrent-requests:8}") int maxConcurrentRequests
    ) {
        if (maxRequestBytes <= 0 || maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("Git request limits must be positive");
        }
        this.maxRequestBytes = maxRequestBytes;
        this.permits = new Semaphore(maxConcurrentRequests, true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/git/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            response.sendError(429);
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
