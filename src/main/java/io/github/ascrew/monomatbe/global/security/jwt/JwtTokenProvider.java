package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * JWT Access/Refresh 토큰 발급 컴포넌트
 *
 * [입력값 검증 원칙]
 * 토큰에 포함되는 모든 클레임은 발급 시점에 검증한다.
 * null/blank 클레임이 포함된 토큰은 JwtAuthenticationFilter에서
 * isValidClaims()로 걸러지지만, 발급 자체를 차단하는 것이 더 안전하다.
 * 문제를 발생 원점에서 즉시 감지할 수 있고,
 * 잘못된 토큰이 Redis나 클라이언트에 전달되는 것을 원천 차단한다.
 */
@Component
public class JwtTokenProvider {

    private static final String ERROR_USER_ID_NULL =
            "토큰 발급 실패: userId는 null일 수 없습니다.";
    private static final String ERROR_USER_TYPE_NULL =
            "토큰 발급 실패: userType은 null일 수 없습니다.";
    private static final String ERROR_USER_ROLE_NULL =
            "토큰 발급 실패: userRole은 null일 수 없습니다.";
    private static final String ERROR_USER_IDENTIFIER_BLANK =
            "토큰 발급 실패: userIdentifier는 null이거나 빈 값일 수 없습니다.";
    private static final String ERROR_SESSION_ID_BLANK =
            "토큰 발급 실패: sessionId는 null이거나 빈 값일 수 없습니다.";

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    @Value("${auth.jwt.refresh-token-validity-seconds:2592000}")
    private long refreshTokenValiditySeconds;

    private SecretKey secretKey;

    /**
     * 애플리케이션 시작 시 JWT 서명 키를 초기화한다.
     * HMAC-SHA 계열 키 길이 요구사항(최소 32바이트)을 강제한다.
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
     * API 인증에 사용하는 단기 Access Token을 발급한다.
     *
     * [클레임 구성]
     * - subject        : userId (DB PK)
     * - userIdentifier : UUID (Redis/WebSocket 식별자)
     * - userType       : GUEST | REGISTERED
     * - userRole       : USER | ADMIN
     * - tokenType      : ACCESS
     */
    public TokenWithExpiry createAccessToken(
            Long userId,
            UserType userType,
            UserRole userRole,
            String userIdentifier
    ) {
        validateCommonClaims(userId, userType, userRole);
        if (!StringUtils.hasText(userIdentifier)) {
            throw new IllegalArgumentException(ERROR_USER_IDENTIFIER_BLANK);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenValiditySeconds);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                        JwtClaims.TOKEN_TYPE, JwtTokenType.ACCESS.name(),
                        JwtClaims.USER_TYPE, userType.name(),
                        JwtClaims.USER_ROLE, userRole.name(),
                        JwtClaims.USER_IDENTIFIER, userIdentifier
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();

        return new TokenWithExpiry(token, expiresAt);
    }

    /**
     * 세션 갱신에 사용하는 장기 Refresh Token을 발급한다.
     *
     * [클레임 구성]
     * - subject   : userId (DB PK)
     * - userType  : GUEST | REGISTERED
     * - sessionId : UUID (Redis refresh key와 동일 식별자)
     * - tokenType : REFRESH
     *
     * [주의]
     * Refresh Token에는 userRole을 넣지 않는다.
     * 권한 변경 후 access token 재발급 시점에는 DB UserSession.user.role 기준으로 최신 권한을 반영한다.
     */
    public TokenWithExpiry createRefreshToken(Long userId, UserType userType, String sessionId) {
        validateCommonClaims(userId, userType);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException(ERROR_SESSION_ID_BLANK);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(refreshTokenValiditySeconds);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(
                        JwtClaims.TOKEN_TYPE, JwtTokenType.REFRESH.name(),
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

    public Duration accessTokenTtl() {
        return Duration.ofSeconds(accessTokenValiditySeconds);
    }

    public Claims parseClaims(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Duration accessTokenRemainingTtl(String accessToken) {
        Claims claims = parseClaims(accessToken);
        Instant expiration = claims.getExpiration().toInstant();
        Duration remaining = Duration.between(Instant.now(), expiration);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private void validateCommonClaims(Long userId, UserType userType) {
        if (userId == null) {
            throw new IllegalArgumentException(ERROR_USER_ID_NULL);
        }
        if (userType == null) {
            throw new IllegalArgumentException(ERROR_USER_TYPE_NULL);
        }
    }

    private void validateCommonClaims(Long userId, UserType userType, UserRole userRole) {
        validateCommonClaims(userId, userType);
        if (userRole == null) {
            throw new IllegalArgumentException(ERROR_USER_ROLE_NULL);
        }
    }
}