package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RefreshTokenResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtClaims;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class RefreshAuthService {

    private static final String ERR_INVALID_REFRESH_TOKEN = "Refresh Token이 유효하지 않습니다.";

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
        LocalDateTime now = LocalDateTime.now();

        String storedRefreshToken = redisTemplate.opsForValue().get(RedisKeys.refreshTokenKey(sessionId));
        UserSession session = userSessionRepository.findBySessionId(sessionId)
                .orElse(null);

        boolean validSession = storedRefreshToken != null
                && storedRefreshToken.equals(refreshToken)
                && session != null
                && session.getSessionToken().equals(refreshToken)
                && session.getUser().getId().equals(userId)
                && session.isActiveAt(now);

        if (!validSession) {
            userSessionLifecycleService.revokeAllActiveSessions(userId, now);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_REFRESH_TOKEN);
        }

        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(userId, userType, sessionId);
        TokenWithExpiry rotatedRefreshToken = jwtTokenProvider.createRefreshToken(userId, userType, sessionId);
        session.rotate(
                rotatedRefreshToken.token(),
                LocalDateTime.ofInstant(rotatedRefreshToken.expiresAt(), ZoneId.systemDefault()),
                now,
                normalizeOptionalLength(ipAddress, 45),
                normalizeOptionalLength(userAgent, 500)
        );

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.opsForValue().set(
                        RedisKeys.refreshTokenKey(sessionId),
                        rotatedRefreshToken.token(),
                        jwtTokenProvider.refreshTokenTtl()
                );
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

    private Claims parseRefreshClaims(String refreshToken) {
        try {
            return jwtTokenProvider.parseClaims(refreshToken);
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_REFRESH_TOKEN);
        }
    }

    private Long extractUserId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_REFRESH_TOKEN);
        }
    }

    private UserType extractUserType(Claims claims) {
        try {
            String value = claims.get(JwtClaims.USER_TYPE, String.class);
            return UserType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_REFRESH_TOKEN);
        }
    }

    private String extractSessionId(Claims claims) {
        String sessionId = claims.get(JwtClaims.SESSION_ID, String.class);
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_REFRESH_TOKEN);
        }
        return sessionId;
    }

    private String normalizeRequired(String value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refresh Token은 비어 있을 수 없습니다.");
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refresh Token은 비어 있을 수 없습니다.");
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
