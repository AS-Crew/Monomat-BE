package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Access Token의 서명, Claim, 토큰 상태와 인증 세션을 검증한다.
 *
 * REST JwtAuthenticationFilter와 STOMP StompChannelInterceptor가
 * 동일한 인증 정책을 사용하도록 검증 책임을 한 곳에 모은다.
 *
 * [검증 순서]
 * 1. JWT 서명 및 만료 검증
 * 2. Access Token 유형 검증
 * 3. 인증에 필요한 Claim 검증
 * 4. Access Token 블랙리스트 검증
 * 5. Redis 활성 세션 검증
 *
 * Redis 조회 실패 시에는 인증 정보를 신뢰하지 않는 fail-closed 정책을 적용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessTokenAuthenticator {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    public AccessTokenAuthentication authenticate(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.INVALID
            );
        }

        try {
            Claims claims = jwtTokenProvider.parseClaims(accessToken);

            validateAccessTokenType(claims);

            AccessTokenAuthentication authentication =
                    parseAuthentication(claims);

            validateNotBlacklisted(accessToken);
            validateActiveSession(authentication.userIdentifier());

            return authentication;

        } catch (AccessTokenAuthenticationException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.EXPIRED,
                    e
            );
        } catch (JwtException
                 | IllegalArgumentException
                 | NullPointerException e) {
            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.INVALID,
                    e
            );
        }
    }

    /**
     * Access Token 전용 Claim을 인증 정보로 변환한다.
     *
     * userRole은 기존 Access Token과의 호환을 위해
     * Claim이 없으면 일반 사용자 권한으로 처리한다.
     */
    private AccessTokenAuthentication parseAuthentication(
            Claims claims
    ) {
        String subject = claims.getSubject();
        String userIdentifier =
                claims.get(JwtClaims.USER_IDENTIFIER, String.class);
        String userTypeValue =
                claims.get(JwtClaims.USER_TYPE, String.class);

        if (!StringUtils.hasText(subject)
                || !StringUtils.hasText(userIdentifier)
                || !StringUtils.hasText(userTypeValue)) {
            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.INVALID
            );
        }

        Long userId = Long.valueOf(subject);
        UserType userType = UserType.valueOf(userTypeValue);
        UserRole userRole = resolveUserRole(claims);

        return new AccessTokenAuthentication(
                userId,
                userIdentifier,
                userType,
                userRole
        );
    }

    /**
     * userRole Claim 도입 이전에 발급된 Access Token을 임시로 지원한다.
     *
     * 기존 토큰은 USER 권한으로만 처리하며,
     * 잘못된 enum 값은 정상 인증으로 간주하지 않는다.
     */
    private UserRole resolveUserRole(Claims claims) {
        String userRoleValue =
                claims.get(JwtClaims.USER_ROLE, String.class);

        if (!StringUtils.hasText(userRoleValue)) {
            return UserRole.USER;
        }

        return UserRole.valueOf(userRoleValue);
    }

    /**
     * 전달된 JWT가 Access Token 용도인지 검증한다.
     *
     * [기존 토큰 호환]
     * tokenType Claim 도입 전에 발급된 Access Token은
     * userIdentifier가 존재하고 Refresh Token 전용 sessionId가 없을 때만 허용한다.
     *
     * 기존 Refresh Token은 sessionId를 포함하므로
     * Access Token 인증에 사용할 수 없다.
     */
    private void validateAccessTokenType(Claims claims) {
        String tokenTypeValue =
                claims.get(JwtClaims.TOKEN_TYPE, String.class);

        if (StringUtils.hasText(tokenTypeValue)) {
            if (!JwtTokenType.ACCESS.name().equals(tokenTypeValue)) {
                throw new AccessTokenAuthenticationException(
                        AccessTokenFailureReason.INVALID
                );
            }

            return;
        }

        String userIdentifier =
                claims.get(JwtClaims.USER_IDENTIFIER, String.class);
        String refreshSessionId =
                claims.get(JwtClaims.SESSION_ID, String.class);

        boolean legacyAccessToken =
                StringUtils.hasText(userIdentifier)
                        && !StringUtils.hasText(refreshSessionId);

        if (!legacyAccessToken) {
            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.INVALID
            );
        }
    }

    /**
     * 로그아웃된 Access Token인지 확인한다.
     * Redis 조회 자체가 실패하면 토큰 상태를 신뢰할 수 없으므로
     * fail-closed 방식으로 인증을 차단한다.
     */
    private void validateNotBlacklisted(String accessToken) {
        try {
            String tokenHash = TokenHashUtils.sha256(accessToken);
            String blacklistKey =
                    RedisKeys.accessTokenBlacklistKey(tokenHash);

            if (Boolean.TRUE.equals(
                    redisTemplate.hasKey(blacklistKey)
            )) {
                throw new AccessTokenAuthenticationException(
                        AccessTokenFailureReason.REVOKED
                );
            }
        } catch (AccessTokenAuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Access Token 블랙리스트 조회 실패 - fail-closed 적용");

            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.AUTHENTICATION_UNAVAILABLE,
                    e
            );
        }
    }

    /**
     * Access Token의 userIdentifier에 대응하는 인증 세션이
     * 현재 Redis에서 활성 상태인지 확인한다.
     */
    private void validateActiveSession(String userIdentifier) {
        try {
            String activeSessionKey =
                    RedisKeys.activeSessionKey(userIdentifier);

            if (!Boolean.TRUE.equals(
                    redisTemplate.hasKey(activeSessionKey)
            )) {
                throw new AccessTokenAuthenticationException(
                        AccessTokenFailureReason.REVOKED
                );
            }
        } catch (AccessTokenAuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("활성 인증 세션 조회 실패 - fail-closed 적용");

            throw new AccessTokenAuthenticationException(
                    AccessTokenFailureReason.AUTHENTICATION_UNAVAILABLE,
                    e
            );
        }
    }
}