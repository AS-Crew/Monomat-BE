package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutAuthServiceTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private UserSessionLifecycleService userSessionLifecycleService;

    @Test
    void logout_blacklistsAccessTokenAndMarksSessionLogout() {
        LogoutAuthService logoutAuthService = new LogoutAuthService(
                jwtTokenProvider,
                redisTemplate,
                userSessionLifecycleService
        );

        String accessToken = "access-token";
        String header = "Bearer " + accessToken;

        when(jwtTokenProvider.accessTokenRemainingTtl(accessToken))
                .thenReturn(Duration.ofMinutes(10));

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        logoutAuthService.logout(1L, "session-1", header);

        verify(valueOperations).set(
                RedisKeys.accessTokenBlacklistKey(TokenHashUtils.sha256(accessToken)),
                "1",
                Duration.ofMinutes(10)
        );

        verify(userSessionLifecycleService).markSessionLogout(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void logout_invalidAuthorizationHeader_throwsInvalidAuthorizationError() {
        LogoutAuthService logoutAuthService = new LogoutAuthService(
                jwtTokenProvider,
                redisTemplate,
                userSessionLifecycleService
        );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> logoutAuthService.logout(1L, "session-1", "invalid")
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_AUTHORIZATION, exception.getErrorCode());
    }

    @Test
    void logout_blankBearerToken_throwsInvalidAuthorizationError() {
        LogoutAuthService logoutAuthService = new LogoutAuthService(
                jwtTokenProvider,
                redisTemplate,
                userSessionLifecycleService
        );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> logoutAuthService.logout(1L, "session-1", "Bearer ")
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_AUTHORIZATION, exception.getErrorCode());
    }

    @Test
    void logout_invalidAccessToken_throwsInvalidAuthorizationError() {
        LogoutAuthService logoutAuthService = new LogoutAuthService(
                jwtTokenProvider,
                redisTemplate,
                userSessionLifecycleService
        );

        String accessToken = "invalid-token";

        when(jwtTokenProvider.accessTokenRemainingTtl(accessToken))
                .thenThrow(new JwtException("invalid token"));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> logoutAuthService.logout(1L, "session-1", "Bearer " + accessToken)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_AUTHORIZATION, exception.getErrorCode());
    }
}