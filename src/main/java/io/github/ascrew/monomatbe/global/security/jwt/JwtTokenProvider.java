package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * JWT Access/Refresh 토큰 발급 컴포넌트
 */
@Component
public class JwtTokenProvider {

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    @Value("${auth.jwt.refresh-token-validity-seconds:2592000}")
    private long refreshTokenValiditySeconds;

    private SecretKey secretKey;

    /**
     * 애플리케이션 시작 시 JWT 서명 키를 초기화합니다.
     * HMAC-SHA 계열 키 길이 요구사항(최소 32바이트)을 강제합니다.
     */
    @PostConstruct
    public void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret은 최소 32바이트 이상이어야 합니다.");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * API 인증에 사용하는 단기 Access Token을 발급합니다.
     * userIdentifier(게스트/회원 공통 UUID)를 claim으로 포함합니다.
     * [클레임 구성]
     * - subject        : userId (DB PK)
     * - userIdentifier : UUID (Redis/WebSocket 식별자)
     * - userType       : GUEST | REGISTERED
     */
    public TokenWithExpiry createAccessToken(Long userId, UserType userType, String userIdentifier) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenValiditySeconds);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                // JwtClaims 상수로 클레임 키 참조 (JwtAuthenticationFilter와 동일한 키)
                .claims(Map.of(
                        JwtClaims.USER_TYPE, userType.name(),
                        JwtClaims.USER_IDENTIFIER, userIdentifier
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();

        return new TokenWithExpiry(token, expiresAt);
    }

    /**
     * 세션 갱신에 사용하는 장기 Refresh Token을 발급합니다.
     * sessionId는 Redis refresh key와 동일 식별자를 사용합니다.
     *
     * [클레임 구성]
     * - subject   : userId (DB PK)
     * - userType  : GUEST | REGISTERED
     * - sessionId : UUID (Redis refresh key와 동일 식별자)
     */
    public TokenWithExpiry createRefreshToken(Long userId, UserType userType, String sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(refreshTokenValiditySeconds);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                // JwtClaims 상수로 클레임 키 참조
                .claims(Map.of(
                        JwtClaims.USER_TYPE, userType.name(),
                        JwtClaims.SESSION_ID, sessionId
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();

        return new TokenWithExpiry(token, expiresAt);
    }

    public Duration refreshTokenTtl() {
        return Duration.ofSeconds(refreshTokenValiditySeconds);
    }
}
