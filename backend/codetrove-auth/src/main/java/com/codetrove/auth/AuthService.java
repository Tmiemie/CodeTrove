package com.codetrove.auth;

import java.time.Instant;
import java.util.Locale;

import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.id.SnowflakeIdGenerator;
import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.common.security.PasswordAuthenticationService;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService implements PasswordAuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator idGenerator;
    private final JwtTokenService jwtTokenService;
    private final String dummyPasswordHash;

    AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        SnowflakeIdGenerator idGenerator,
        JwtTokenService jwtTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.jwtTokenService = jwtTokenService;
        this.dummyPasswordHash = passwordEncoder.encode("codetrove-dummy-password");
    }

    @Transactional
    AuthenticatedUser register(String username, String password, String displayName) {
        String normalizedUsername = normalizeUsername(username);
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            throw new BusinessException(ErrorCode.USERNAME_CONFLICT);
        }
        try {
            UserAccount account = userRepository.create(
                idGenerator.nextId(),
                normalizedUsername,
                passwordEncoder.encode(password),
                displayName.trim()
            );
            return account.toAuthenticatedUser();
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.USERNAME_CONFLICT);
        }
    }

    @Override
    public AuthenticatedUser authenticate(String username, String password) {
        return authenticateAccount(username, password).toAuthenticatedUser();
    }

    AuthResult login(String username, String password) {
        AuthenticatedUser user = authenticateAccount(username, password).toAuthenticatedUser();
        JwtTokenService.IssuedToken token = jwtTokenService.issue(user);
        return new AuthResult(user, token.value(), token.expiresAt());
    }

    private UserAccount authenticateAccount(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        UserAccount account = userRepository.findByUsername(normalizedUsername).orElse(null);
        String passwordHash = account == null ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);
        if (account == null || account.status() != UserStatus.ACTIVE || !passwordMatches) {
            throw invalidCredentials();
        }
        return account;
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    record AuthResult(AuthenticatedUser user, String accessToken, Instant expiresAt) {
    }
}
