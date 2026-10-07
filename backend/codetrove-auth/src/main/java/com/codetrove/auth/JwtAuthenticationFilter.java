package com.codetrove.auth;

import java.io.IOException;

import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.security.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;
    private final SecurityErrorWriter errorWriter;

    JwtAuthenticationFilter(
        JwtTokenService jwtTokenService,
        UserRepository userRepository,
        SecurityErrorWriter errorWriter
    ) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/git/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            errorWriter.write(response, ErrorCode.AUTH_TOKEN_INVALID);
            return;
        }
        try {
            long userId = jwtTokenService.parseUserId(authorization.substring(BEARER_PREFIX.length()));
            AuthenticatedUser user = userRepository.findById(userId)
                .filter(account -> account.status() == UserStatus.ACTIVE)
                .map(UserAccount::toAuthenticatedUser)
                .orElseThrow(() -> new JwtTokenService.TokenInvalidException(
                    new IllegalStateException("User not found or inactive")
                ));
            UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, userAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtTokenService.TokenExpiredException exception) {
            errorWriter.write(response, ErrorCode.AUTH_TOKEN_EXPIRED);
        } catch (JwtTokenService.TokenInvalidException exception) {
            errorWriter.write(response, ErrorCode.AUTH_TOKEN_INVALID);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private java.util.List<org.springframework.security.core.GrantedAuthority> userAuthorities() {
        return java.util.List.of(() -> "ROLE_USER");
    }
}
