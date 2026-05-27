package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAuthService {

    private static final int LOCK_THRESHOLD = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final int MIN_LOGIN_ID_LENGTH = 4;
    private static final int MAX_LOGIN_ID_LENGTH = 50;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 100;

    private static final String LOGIN_ID_PATTERN = "^[A-Za-z0-9]+$";

    private final UserCredentialRepository userCredentialRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserSessionLifecycleService userSessionLifecycleService;

    @Value("${auth.redis.refresh-store-enabled:true}")
    private boolean refreshStoreEnabled;

    /**
     * 로그인 실패 시 failedLoginCount 증가를 커밋해야 하므로
     * 인증 실패 예외(AuthException)는 트랜잭션 롤백 대상에서 제외한다.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public LoginResponse login(String rawLoginId, String rawPassword, String ipAddress, String userAgent) {
        String loginId = normalizeLoginId(rawLoginId);
        String password = normalizePassword(rawPassword);

        UserCredential credential = userCredentialRepository.findByLoginId(loginId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS));

        LocalDateTime now = LocalDateTime.now();

        if (credential.isLockedAt(now)) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            credential.increaseFailedLoginCount();

            if (credential.getFailedLoginCount() >= LOCK_THRESHOLD) {
                credential.lockUntil(now.plus(LOCK_DURATION));
            }

            throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        credential.resetFailedLoginState();

        User user = credential.getUser();
        user.updateLastLoginAt(now);

        UserType userType = user.getUserType();
        String userIdentifier = UUID.randomUUID().toString();

        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                userType,
                userIdentifier
        );

        TokenWithExpiry refreshToken = jwtTokenProvider.createRefreshToken(
                user.getId(),
                userType,
                userIdentifier
        );

        String refreshTokenHash = TokenHashUtils.sha256(refreshToken.token());

        userSessionRepository.save(UserSession.builder()
                .user(user)
                .sessionId(userIdentifier)
                .sessionToken(refreshTokenHash)
                .ipAddress(normalizeOptionalLength(ipAddress, 45))
                .userAgent(normalizeOptionalLength(userAgent, 500))
                .expiresAt(LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneId.systemDefault()))
                .createdAt(now)
                .updatedAt(now)
                .status(UserSessionStatus.ACTIVE)
                .build());

        userSessionLifecycleService.enforceActiveSessionLimit(
                user.getId(),
                userType,
                userIdentifier,
                now
        );

        if (refreshStoreEnabled) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    String refreshTokenKey = RedisKeys.refreshTokenKey(userIdentifier);

                    try {
                        redisTemplate.opsForValue().set(
                                refreshTokenKey,
                                refreshTokenHash,
                                jwtTokenProvider.refreshTokenTtl()
                        );
                        redisTemplate.opsForValue().set(
                                RedisKeys.activeSessionKey(userIdentifier),
                                "1",
                                jwtTokenProvider.refreshTokenTtl()
                        );
                    } catch (RuntimeException e) {
                        log.error("로그인 후 Redis 세션 저장 실패 - sessionId: {}", userIdentifier, e);
                        userSessionLifecycleService.markSessionRevokedCompensating(
                                userIdentifier,
                                LocalDateTime.now()
                        );
                    }
                }
            });
        }

        return LoginResponse.builder()
                .userId(user.getId())
                .loginId(credential.getLoginId())
                .nickname(user.getUsername())
                .userType(user.getUserType())
                .userIdentifier(userIdentifier)
                .accessToken(accessToken.token())
                .accessTokenExpiresAt(accessToken.expiresAt())
                .refreshToken(refreshToken.token())
                .refreshTokenExpiresAt(refreshToken.expiresAt())
                .build();
    }

    private String normalizeLoginId(String value) {
        String loginId = normalizeRequiredWithTrim(value, AuthErrorCode.AUTH_LOGIN_ID_REQUIRED);

        if (containsWhitespace(loginId)) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_CONTAINS_WHITESPACE);
        }

        if (loginId.length() < MIN_LOGIN_ID_LENGTH || loginId.length() > MAX_LOGIN_ID_LENGTH) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_INVALID_LENGTH);
        }

        if (!loginId.matches(LOGIN_ID_PATTERN)) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_INVALID_FORMAT);
        }

        return loginId;
    }

    private String normalizePassword(String value) {
        String password = normalizeRequired(value, AuthErrorCode.AUTH_PASSWORD_REQUIRED);

        if (containsWhitespace(password)) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE);
        }

        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_INVALID_LENGTH);
        }

        return password;
    }

    private String normalizeRequiredWithTrim(String value, AuthErrorCode requiredErrorCode) {
        String normalized = normalizeRequired(value, requiredErrorCode).trim();

        if (normalized.isBlank()) {
            throw new AuthException(requiredErrorCode);
        }

        return normalized;
    }

    private String normalizeRequired(String value, AuthErrorCode requiredErrorCode) {
        if (value == null || value.isBlank()) {
            throw new AuthException(requiredErrorCode);
        }

        return value;
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private String normalizeOptionalLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.isEmpty()) {
            return null;
        }

        return normalized.length() > maxLength
                ? normalized.substring(0, maxLength)
                : normalized;
    }
}