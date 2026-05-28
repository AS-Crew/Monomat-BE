package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RefreshTokenResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
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
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshAuthServiceTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private UserSessionLifecycleService userSessionLifecycleService;

    private RefreshAuthService refreshAuthService;

    @BeforeEach
    void setUp() {
        refreshAuthService = new RefreshAuthService(
                jwtTokenProvider,
                redisTemplate,
                userSessionRepository,
                userSessionLifecycleService
        );

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void refresh_success_rotatesTokens() {
        String requestRefreshToken = "refresh-token-old";
        String sessionId = "session-123";
        Long userId = 1L;

        Claims claims = mockRefreshClaims(userId, UserType.REGISTERED, sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.refreshTokenKey(sessionId)))
                .thenReturn(TokenHashUtils.sha256(requestRefreshToken));

        UserSession session = activeSession(
                userId,
                sessionId,
                TokenHashUtils.sha256(requestRefreshToken),
                LocalDateTime.now().plusMinutes(10)
        );

        when(userSessionRepository.findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        TokenWithExpiry accessToken = new TokenWithExpiry(
                "access-token-new",
                Instant.now().plusSeconds(600)
        );

        TokenWithExpiry refreshToken = new TokenWithExpiry(
                "refresh-token-new",
                Instant.now().plusSeconds(3600)
        );

        when(jwtTokenProvider.createAccessToken(userId, UserType.REGISTERED, sessionId))
                .thenReturn(accessToken);
        when(jwtTokenProvider.createRefreshToken(userId, UserType.REGISTERED, sessionId))
                .thenReturn(refreshToken);

        RefreshTokenResponse response = refreshAuthService.refresh(
                requestRefreshToken,
                "127.0.0.1",
                "JUnit"
        );

        assertEquals(userId, response.userId());
        assertEquals(UserType.REGISTERED, response.userType());
        assertEquals(sessionId, response.userIdentifier());
        assertEquals("access-token-new", response.accessToken());
        assertEquals("refresh-token-new", response.refreshToken());
        assertEquals(TokenHashUtils.sha256("refresh-token-new"), session.getSessionToken());
        assertEquals(
                LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneId.systemDefault()),
                session.getExpiresAt()
        );
    }

    @Test
    void refresh_mismatch_detectsReuseAndRevokesAll() {
        String requestRefreshToken = "refresh-token-old";
        String sessionId = "session-123";
        Long userId = 1L;

        Claims claims = mockRefreshClaims(userId, UserType.REGISTERED, sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        UserSession session = activeSession(
                userId,
                sessionId,
                "different-hash",
                LocalDateTime.now().plusMinutes(10)
        );

        when(userSessionRepository.findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh(requestRefreshToken, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, exception.getErrorCode());

        verify(userSessionLifecycleService)
                .revokeAllActiveSessions(eq(userId), any(LocalDateTime.class));
    }

    @Test
    void refresh_invalidJwt_throwsInvalidRefreshTokenError() {
        String requestRefreshToken = "invalid-refresh-token";

        when(jwtTokenProvider.parseClaims(requestRefreshToken))
                .thenThrow(new JwtException("invalid refresh token"));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh(requestRefreshToken, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, exception.getErrorCode());
    }

    @Test
    void refresh_missingSession_throwsInvalidRefreshTokenError() {
        String requestRefreshToken = "refresh-token-old";
        String sessionId = "session-123";
        Long userId = 1L;

        Claims claims = mockRefreshClaims(userId, UserType.REGISTERED, sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        when(userSessionRepository.findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.empty());

        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh(requestRefreshToken, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, exception.getErrorCode());
    }

    @Test
    void refresh_inactiveSession_throwsSessionExpiredError() {
        String requestRefreshToken = "refresh-token-old";
        String sessionId = "session-123";
        Long userId = 1L;

        Claims claims = mockRefreshClaims(userId, UserType.REGISTERED, sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        UserSession session = activeSession(
                userId,
                sessionId,
                TokenHashUtils.sha256(requestRefreshToken),
                LocalDateTime.now().minusMinutes(1)
        );

        when(userSessionRepository.findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh(requestRefreshToken, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_SESSION_EXPIRED, exception.getErrorCode());
    }

    @Test
    void refresh_nullRefreshToken_throwsRefreshTokenRequiredError() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh(null, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED, exception.getErrorCode());
    }

    @Test
    void refresh_blankRefreshToken_throwsRefreshTokenRequiredError() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh("   ", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED, exception.getErrorCode());
    }

    @Test
    void refresh_redisFailure_throwsTemporaryUnavailableError() {
        String requestRefreshToken = "refresh-token-old";
        String sessionId = "session-123";
        Long userId = 1L;

        Claims claims = mockRefreshClaims(userId, UserType.REGISTERED, sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        UserSession session = activeSession(
                userId,
                sessionId,
                TokenHashUtils.sha256(requestRefreshToken),
                LocalDateTime.now().plusMinutes(10)
        );

        when(userSessionRepository.findBySessionIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        when(redisTemplate.opsForValue())
                .thenThrow(new IllegalStateException("Redis unavailable"));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshAuthService.refresh(requestRefreshToken, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE, exception.getErrorCode());
    }

    private Claims mockRefreshClaims(Long userId, UserType userType, String sessionId) {
        Claims claims = mock(Claims.class);

        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        when(claims.get(JwtClaims.USER_TYPE, String.class)).thenReturn(userType.name());
        when(claims.get(JwtClaims.SESSION_ID, String.class)).thenReturn(sessionId);

        return claims;
    }

    private UserSession activeSession(
            Long userId,
            String sessionId,
            String sessionToken,
            LocalDateTime expiresAt
    ) {
        return UserSession.builder()
                .user(User.builder()
                        .id(userId)
                        .username("tester")
                        .userType(UserType.REGISTERED)
                        .status(UserStatus.ACTIVE)
                        .build())
                .sessionId(sessionId)
                .sessionToken(sessionToken)
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .updatedAt(LocalDateTime.now().minusMinutes(1))
                .status(UserSessionStatus.ACTIVE)
                .build();
    }
}