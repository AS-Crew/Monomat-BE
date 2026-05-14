package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RefreshTokenResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtClaims;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        when(claims.get(JwtClaims.USER_TYPE, String.class)).thenReturn(UserType.REGISTERED.name());
        when(claims.get(JwtClaims.SESSION_ID, String.class)).thenReturn(sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.refreshTokenKey(sessionId))).thenReturn(requestRefreshToken);

        UserSession session = UserSession.builder()
                .user(User.builder().id(userId).username("tester").userType(UserType.REGISTERED).status(UserStatus.ACTIVE).build())
                .sessionId(sessionId)
                .sessionToken(requestRefreshToken)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .updatedAt(LocalDateTime.now().minusMinutes(1))
                .status(UserSessionStatus.ACTIVE)
                .build();
        when(userSessionRepository.findBySessionId(sessionId)).thenReturn(Optional.of(session));

        TokenWithExpiry accessToken = new TokenWithExpiry("access-token-new", Instant.now().plusSeconds(600));
        TokenWithExpiry refreshToken = new TokenWithExpiry("refresh-token-new", Instant.now().plusSeconds(3600));
        when(jwtTokenProvider.createAccessToken(userId, UserType.REGISTERED, sessionId)).thenReturn(accessToken);
        when(jwtTokenProvider.createRefreshToken(userId, UserType.REGISTERED, sessionId)).thenReturn(refreshToken);

        RefreshTokenResponse response = refreshAuthService.refresh(requestRefreshToken, "127.0.0.1", "JUnit");

        assertEquals(userId, response.userId());
        assertEquals(UserType.REGISTERED, response.userType());
        assertEquals(sessionId, response.userIdentifier());
        assertEquals("access-token-new", response.accessToken());
        assertEquals("refresh-token-new", response.refreshToken());
        assertEquals("refresh-token-new", session.getSessionToken());
        assertEquals(LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneId.systemDefault()), session.getExpiresAt());
    }

    @Test
    void refresh_mismatch_detectsReuseAndRevokesAll() {
        String requestRefreshToken = "refresh-token-old";
        String sessionId = "session-123";
        Long userId = 1L;

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        when(claims.get(JwtClaims.USER_TYPE, String.class)).thenReturn(UserType.REGISTERED.name());
        when(claims.get(JwtClaims.SESSION_ID, String.class)).thenReturn(sessionId);
        when(jwtTokenProvider.parseClaims(requestRefreshToken)).thenReturn(claims);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.refreshTokenKey(sessionId))).thenReturn("different-token");
        when(userSessionRepository.findBySessionId(sessionId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> refreshAuthService.refresh(requestRefreshToken, null, null)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Refresh Token이 유효하지 않습니다.", exception.getReason());
        org.mockito.Mockito.verify(userSessionLifecycleService).revokeAllActiveSessions(eq(userId), any(LocalDateTime.class));
    }
}
