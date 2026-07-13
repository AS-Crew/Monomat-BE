package io.github.ascrew.monomatbe.global.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * HTTP 요청의 JWT Access Token을 검증하고
 * Spring SecurityContext에 인증 정보를 저장하는 필터.
 *
 * 실제 JWT 검증, Claim 해석, 블랙리스트 조회 및 활성 세션 검증은
 * AccessTokenAuthenticator에 위임한다.
 *
 * REST와 STOMP가 같은 인증 컴포넌트를 사용함으로써
 * 두 프로토콜의 인증 정책이 달라지는 문제를 방지한다.
 *
 * [인증 실패 정책]
 * Access Token 인증에 실패하면 SecurityContext를 비우지만,
 * 필터 체인은 계속 진행한다.
 *
 * 따라서 인증이 필요한 엔드포인트의 최종 접근 차단은
 * 기존 Spring Security 인가 설정에서 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessTokenAuthenticator accessTokenAuthenticator;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String accessToken = extractToken(request);

        if (accessToken != null) {
            authenticate(accessToken);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Access Token을 검증하고 SecurityContext에 인증 정보를 저장한다.
     *
     * 인증 실패 원인은 로그에 구분하여 남기지만,
     * JWT 원문이나 내부 예외 메시지는 로그에 출력하지 않는다.
     */
    private void authenticate(String accessToken) {
        try {
            AccessTokenAuthentication tokenAuthentication =
                    accessTokenAuthenticator.authenticate(accessToken);

            CustomPrincipal principal =
                    tokenAuthentication.toPrincipal();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            JwtClaims.ROLE_PREFIX
                                                    + principal.role().name()
                                    )
                            )
                    );

            SecurityContextHolder.getContext()
                    .setAuthentication(authentication);

        } catch (AccessTokenAuthenticationException e) {
            log.warn(
                    "Access Token 인증 실패 - reason: {}",
                    e.getReason()
            );

            SecurityContextHolder.clearContext();
        }
    }

    /**
     * HTTP Authorization 헤더에서 Bearer Access Token을 추출한다.
     *
     * 헤더가 없거나 Bearer 형식이 아니거나 값이 비어 있으면
     * 인증 정보가 없는 요청으로 처리한다.
     */
    private String extractToken(HttpServletRequest request) {
        String authorization =
                request.getHeader(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(authorization)
                || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String accessToken =
                authorization.substring(BEARER_PREFIX.length())
                        .trim();

        return StringUtils.hasText(accessToken)
                ? accessToken
                : null;
    }
}