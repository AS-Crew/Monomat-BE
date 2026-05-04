package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
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
 * JWT Access/Refresh 토큰 발급 컴포넌트.
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

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_USER_ID_NULL =
            "토큰 발급 실패: userId는 null일 수 없습니다.";
    private static final String ERROR_USER_TYPE_NULL =
            "토큰 발급 실패: userType은 null일 수 없습니다.";
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
     *
     * [입력값 검증]
     * userIdentifier가 null이거나 빈 문자열이면 IllegalArgumentException을 던진다.
     * 잘못된 클레임이 포함된 토큰이 발급되어 JwtAuthenticationFilter의
     * isValidClaims()에서 인증 실패로 처리되는 상황을 발급 시점에 차단한다.
     *
     * @param userId         DB users.id (subject 클레임)
     * @param userType       사용자 유형 (GUEST | REGISTERED)
     * @param userIdentifier Redis/WebSocket 식별자 (UUID)
     * @return 발급된 토큰과 만료시각
     * @throws IllegalArgumentException userIdentifier가 null이거나 빈 값인 경우
     */
    public TokenWithExpiry createAccessToken(Long userId, UserType userType, String userIdentifier) {
        // 발급 시점 입력값 검증
        validateCommonClaims(userId, userType);
        if (!StringUtils.hasText(userIdentifier)) {
            throw new IllegalArgumentException(ERROR_USER_IDENTIFIER_BLANK);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenValiditySeconds);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
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
     * 세션 갱신에 사용하는 장기 Refresh Token을 발급한다.
     *
     * [클레임 구성]
     * - subject   : userId (DB PK)
     * - userType  : GUEST | REGISTERED
     * - sessionId : UUID (Redis refresh key와 동일 식별자)
     *
     * [입력값 검증]
     * sessionId가 null이거나 빈 문자열이면 IllegalArgumentException을 던진다.
     * 유효하지 않은 sessionId로 Refresh Token이 발급되면
     * Redis에 저장된 토큰과 불일치가 발생하여 갱신이 불가능해진다.
     *
     * @param userId    DB users.id (subject 클레임)
     * @param userType  사용자 유형 (GUEST | REGISTERED)
     * @param sessionId Redis refresh key 식별자 (UUID)
     * @return 발급된 토큰과 만료시각
     * @throws IllegalArgumentException sessionId가 null이거나 빈 값인 경우
     */
    public TokenWithExpiry createRefreshToken(Long userId, UserType userType, String sessionId) {
        // 발급 시점 입력값 검증
        validateCommonClaims(userId, userType);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException(ERROR_SESSION_ID_BLANK);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(refreshTokenValiditySeconds);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
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

    // =========================================================
    // private 메서드
    // =========================================================

    /**
     * Access/Refresh 토큰 발급 시 공통으로 검증하는 클레임을 확인한다.
     *
     * [검증 항목]
     * - userId    : null이면 subject 클레임을 구성할 수 없음
     * - userType  : null이면 권한 클레임을 구성할 수 없음
     *
     * @throws IllegalArgumentException userId 또는 userType이 null인 경우
     */
    private void validateCommonClaims(Long userId, UserType userType) {
        if (userId == null) {
            throw new IllegalArgumentException(ERROR_USER_ID_NULL);
        }
        if (userType == null) {
            throw new IllegalArgumentException(ERROR_USER_TYPE_NULL);
        }
    }
}