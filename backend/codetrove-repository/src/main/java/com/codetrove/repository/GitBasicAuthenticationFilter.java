package com.codetrove.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.common.security.PasswordAuthenticationService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class GitBasicAuthenticationFilter extends OncePerRequestFilter {

    private static final String BASIC_PREFIX = "Basic ";
    private static final String REALM = "Basic realm=\"CodeTrove Git\", charset=\"UTF-8\"";

    private final PasswordAuthenticationService authenticationService;

    GitBasicAuthenticationFilter(PasswordAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
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
        Credentials credentials = credentials(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (credentials == null) {
            unauthorized(response);
            return;
        }
        try {
            AuthenticatedUser user = authenticationService.authenticate(
                credentials.username(),
                credentials.password()
            );
            request.setAttribute(GitRequestContext.AUTHENTICATED_USER, user);
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            unauthorized(response);
        }
    }

    private Credentials credentials(String authorization) {
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return null;
        }
        try {
            String decoded = new String(
                Base64.getDecoder().decode(authorization.substring(BASIC_PREFIX.length())),
                StandardCharsets.UTF_8
            );
            int separator = decoded.indexOf(':');
            if (separator <= 0) {
                return null;
            }
            return new Credentials(decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, REALM);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private record Credentials(String username, String password) {
    }
}
