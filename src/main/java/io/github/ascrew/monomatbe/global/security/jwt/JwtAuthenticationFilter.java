package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT Access Token을 검증하고 SecurityContext에 인증 정보를 저장하는 필터.
 *
 * [처리 흐름]
 * 1. Authorization 헤더에서 Bearer 토큰 추출
 * 2. JWT 파싱 및 서명 검증
 * 3. userId, userIdentifier, userType 클레임 추출
 * 4. userRole 클레임 추출. 기존 토큰 호환을 위해 누락 시 USER로 처리
 * 5. CustomPrincipal 생성 후 SecurityContext 저장
 *
 * [하위호환성 정책]
 * userRole claim은 #122에서 새로 추가된 claim이다.
 * 배포 전 발급된 기존 Access Token에는 userRole이 없을 수 있으므로,
 * 전환 기간 동안 userRole 누락 토큰은 USER 권한으로 처리한다.
 *
 * 잘못된 userRole 값은 위변조 또는 비정상 토큰으로 보고 인증 실패 처리한다.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    private final SecretKey secretKey;
    private final StringRedisTemplate redisTemplate;

    public JwtAuthenticationFilter(
            @Value("${auth.jwt.secret}") String secret,
            StringRedisTemplate redisTemplate
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null) {
            try {
                if (isBlacklistedAccessToken(token)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }

                Claims claims = Jwts.parser()
                        .verifyWith(secretKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                if (!isValidClaims(claims)) {
                    log.warn("JWT 클레임 누락 또는 유효하지 않음 - 인증 거부");
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }

                Long userId = Long.valueOf(claims.getSubject());
                String userIdentifier = claims.get(JwtClaims.USER_IDENTIFIER, String.class);
                UserType userType = UserType.valueOf(claims.get(JwtClaims.USER_TYPE, String.class));
                UserRole userRole = resolveUserRole(claims);

                if (!isActiveSession(userIdentifier)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }

                CustomPrincipal principal = new CustomPrincipal(
                        userId,
                        userIdentifier,
                        userType,
                        userRole
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                List.of(new SimpleGrantedAuthority(
                                        JwtClaims.ROLE_PREFIX + userRole.name()))
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException e) {
                log.warn("JWT 검증 실패: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("JWT 클레임 파싱 실패 - 인증 거부: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 인증에 필요한 Access Token 클레임이 모두 존재하고 유효한지 검증한다.
     *
     * [검증 항목]
     * - subject        : userId
     * - userIdentifier : Redis/WebSocket 세션 식별자
     * - userType       : GUEST | REGISTERED
     *
     * [userRole 제외 이유]
     * userRole은 #122에서 추가된 claim이므로 기존 발급 토큰에는 없을 수 있다.
     * 누락 시 USER로 fallback 처리한다.
     */
    private boolean isValidClaims(Claims claims) {
        String subject = claims.getSubject();
        String userIdentifier = claims.get(JwtClaims.USER_IDENTIFIER, String.class);
        String userType = claims.get(JwtClaims.USER_TYPE, String.class);

        return subject != null && !subject.isBlank()
                && userIdentifier != null
                && userType != null;
    }

    /**
     * userRole claim을 권한 enum으로 변환한다.
     *
     * [전환 기간 하위호환성]
     * 기존 Access Token에는 userRole claim이 없으므로 USER로 간주한다.
     *
     * [보안]
     * claim 값이 존재하지만 UserRole enum에 없는 값이면 IllegalArgumentException을 발생시켜
     * 인증 실패로 처리한다.
     */
    private UserRole resolveUserRole(Claims claims) {
        String userRole = claims.get(JwtClaims.USER_ROLE, String.class);

        if (userRole == null || userRole.isBlank()) {
            return UserRole.USER;
        }

        return UserRole.valueOf(userRole);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }

        return null;
    }

    private boolean isBlacklistedAccessToken(String accessToken) {
        try {
            String tokenHash = TokenHashUtils.sha256(accessToken);
            String key = RedisKeys.accessTokenBlacklistKey(tokenHash);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (RuntimeException e) {
            log.warn("블랙리스트 조회 실패 - fail-closed 적용");
            return true;
        }
    }

    private boolean isActiveSession(String sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.activeSessionKey(sessionId)));
        } catch (RuntimeException e) {
            log.warn("활성 세션 조회 실패 - fail-closed 적용");
            return false;
        }
    }
}