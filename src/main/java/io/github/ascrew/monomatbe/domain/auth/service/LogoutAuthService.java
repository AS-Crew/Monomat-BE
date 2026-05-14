package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LogoutAuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserSessionLifecycleService userSessionLifecycleService;

    @Transactional
    public void logout(Long userId, String sessionId, String authorizationHeader) {
        String accessToken = extractBearerToken(authorizationHeader);
        Duration ttl;
        try {
            ttl = jwtTokenProvider.accessTokenRemainingTtl(accessToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization 헤더가 유효하지 않습니다.");
        }
        if (!ttl.isZero() && !ttl.isNegative()) {
            String hash = TokenHashUtils.sha256(accessToken);
            redisTemplate.opsForValue().set(RedisKeys.accessTokenBlacklistKey(hash), "1", ttl);
        }

        userSessionLifecycleService.markSessionLogout(userId, sessionId, LocalDateTime.now());
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization 헤더가 유효하지 않습니다.");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization 헤더가 유효하지 않습니다.");
        }
        return token;
    }
}
