package com.codetrove.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.codetrove.common.security.AuthenticatedUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class JwtTokenService {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final String issuer;

    JwtTokenService(
        @Value("${codetrove.auth.jwt-secret}") String jwtSecret,
        @Value("${codetrove.auth.access-token-ttl:15m}") Duration accessTokenTtl,
        @Value("${codetrove.auth.issuer:codetrove}") String issuer
    ) {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenTtl = accessTokenTtl;
        this.issuer = issuer;
    }

    IssuedToken issue(AuthenticatedUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        String token = Jwts.builder()
            .issuer(issuer)
            .subject(Long.toString(user.id()))
            .claim("username", user.username())
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey)
            .compact();
        return new IssuedToken(token, expiresAt);
    }

    long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (ExpiredJwtException exception) {
            throw new TokenExpiredException(exception);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new TokenInvalidException(exception);
        }
    }

    record IssuedToken(String value, Instant expiresAt) {
    }

    static final class TokenExpiredException extends RuntimeException {
        TokenExpiredException(Exception cause) {
            super(cause);
        }
    }

    static final class TokenInvalidException extends RuntimeException {
        TokenInvalidException(Exception cause) {
            super(cause);
        }
    }
}
