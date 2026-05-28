package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RefreshTokenResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtClaims;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshAuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserSessionRepository userSessionRepository;
    private final UserSessionLifecycleService userSessionLifecycleService;

    @Transactional
    public RefreshTokenResponse refresh(String rawRefreshToken, String ipAddress, String userAgent) {
        String refreshToken = normalizeRequired(rawRefreshToken);
        Claims claims = parseRefreshClaims(refreshToken);

        Long userId = extractUserId(claims);
        UserType userType = extractUserType(claims);
        String sessionId = extractSessionId(claims);
        String refreshTokenHash = TokenHashUtils.sha256(refreshToken);
        LocalDateTime now = LocalDateTime.now();

        UserSession session = userSessionRepository.findBySessionIdForUpdate(sessionId)
                .orElse(null);

        if (session == null || !session.getUser().getId().equals(userId)) {
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        if (!session.isActiveAt(now)) {
            throw new AuthException(AuthErrorCode.AUTH_SESSION_EXPIRED);
        }

        if (!matchesStoredRefreshToken(session.getSessionToken(), refreshToken, refreshTokenHash)) {
            userSessionLifecycleService.revokeAllActiveSessions(userId, now);
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        String storedRefreshTokenHash;
        try {
            storedRefreshTokenHash = redisTemplate.opsForValue().get(RedisKeys.refreshTokenKey(sessionId));
        } catch (RuntimeException e) {
            throw new AuthException(AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE, e);
        }

        if (storedRefreshTokenHash != null
                && !matchesStoredRefreshToken(storedRefreshTokenHash, refreshToken, refreshTokenHash)) {
            userSessionLifecycleService.revokeAllActiveSessions(userId, now);
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(userId, userType, sessionId);
        TokenWithExpiry rotatedRefreshToken = jwtTokenProvider.createRefreshToken(userId, userType, sessionId);
        String rotatedRefreshTokenHash = TokenHashUtils.sha256(rotatedRefreshToken.token());

        session.rotate(
                rotatedRefreshTokenHash,
                LocalDateTime.ofInstant(rotatedRefreshToken.expiresAt(), ZoneId.systemDefault()),
                now,
                normalizeOptionalLength(ipAddress, 45),
                normalizeOptionalLength(userAgent, 500)
        );

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisTemplate.opsForValue().set(
                            RedisKeys.refreshTokenKey(sessionId),
                            rotatedRefreshTokenHash,
                            jwtTokenProvider.refreshTokenTtl()
                    );
                    redisTemplate.opsForValue().set(
                            RedisKeys.activeSessionKey(sessionId),
                            "1",
                            jwtTokenProvider.refreshTokenTtl()
                    );
                } catch (RuntimeException e) {
                    log.error("Refresh 후 Redis 갱신 실패 - sessionId: {}", sessionId, e);
                    userSessionLifecycleService.markSessionRevokedCompensating(
                            sessionId,
                            LocalDateTime.now()
                    );
                }
            }
        });

        return RefreshTokenResponse.builder()
                .userId(userId)
                .userType(userType)
                .userIdentifier(sessionId)
                .accessToken(accessToken.token())
                .accessTokenExpiresAt(accessToken.expiresAt())
                .refreshToken(rotatedRefreshToken.token())
                .refreshTokenExpiresAt(rotatedRefreshToken.expiresAt())
                .build();
    }

    private boolean matchesStoredRefreshToken(String storedValue, String requestRawToken, String requestHash) {
        if (storedValue == null || storedValue.isBlank()) {
            return false;
        }

        return storedValue.equals(requestHash) || storedValue.equals(requestRawToken);
    }

    private Claims parseRefreshClaims(String refreshToken) {
        try {
            return jwtTokenProvider.parseClaims(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new AuthException(AuthErrorCode.AUTH_SESSION_EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, e);
        }
    }

    private Long extractUserId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, e);
        }
    }

    private UserType extractUserType(Claims claims) {
        try {
            String value = claims.get(JwtClaims.USER_TYPE, String.class);
            return UserType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, e);
        }
    }

    private String extractSessionId(Claims claims) {
        String sessionId = claims.get(JwtClaims.SESSION_ID, String.class);

        if (sessionId == null || sessionId.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        return sessionId;
    }

    private String normalizeRequired(String value) {
        if (value == null) {
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED);
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED);
        }

        return normalized;
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