package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
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
 * 3. userId, userIdentifier, userType 클레임 추출 (JwtClaims 상수 사용)
 * 4. 클레임 null 검증 — 하나라도 누락 시 인증 실패 처리
 * 5. CustomPrincipal 생성 후 SecurityContext 저장
 *
 * [토큰 없는 요청 처리]
 * permitAll 경로는 토큰 없이 통과시킨다.
 * 경로별 접근 제어는 SecurityConfig의 authorizeHttpRequests에서 담당한다.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Authorization 헤더에 들어가는 토큰의 접두사 (공백 포함)
    private static final String BEARER_PREFIX = "Bearer ";
    // 헤더 키 값
    private static final String HEADER_AUTHORIZATION = "Authorization";

    // JWT 서명 검증에 사용할 암호화 키
    private final SecretKey secretKey;

    /**
     * 필터 생성자
     * application.yml (또는 properties) 파일에서 'auth.jwt.secret' 값을 주입받아 SecretKey 객체로 초기화한다.     */
    public JwtAuthenticationFilter(@Value("${auth.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. 요청 헤더에서 JWT 토큰 문자열을 추출
        String token = extractToken(request);

        // 2. 토큰이 존재할 경우에만 검증 로직 수행
        // 토큰이 없으면 그냥 다음 필터로 넘어감 -> permitAll이면 통과, 아니면 인가 예외 발생
        if (token != null) {
            try {
                // 3. JWT 문자열을 파싱하고 서명을 검증하여 Payload (클레임)를 가져옴
                Claims claims = Jwts.parser()
                        .verifyWith(secretKey) // 서버가 가진 secretKey로 서명 (Signature) 위변조 확인
                        .build()
                        .parseSignedClaims(token) // 토큰 유휴성 (만료 시간 등) 검사 및 파싱
                        .getPayload(); // 파싱된 데이터 (Claims) 추출

                // 4. 클레임 추출 후 필수 데이터 null 검증
                // subject, userIdentifier, userType 중 하나라도 누락되면
                // 위변조 또는 잘못된 토큰으로 간주하여 인증 실패 처리
                if (!isValidClaims(claims)) {
                    log.warn("JWT 클레임 누락 또는 유효하지 않음 - 인증 거부");
                    SecurityContextHolder.clearContext(); // 비정상 토큰이므로 현재 스레드의 시큐리티 컨텍스트 초기화
                    filterChain.doFilter(request, response); // 다음 필터로 넘김 (인증되지 않은 상태로 진행됨)
                    return;
                }

                // 5. 토큰 클레임에서 사용자 정보 추출
                Long userId = Long.valueOf(claims.getSubject());
                String userIdentifier = claims.get(JwtClaims.USER_IDENTIFIER, String.class);
                UserType userType = UserType.valueOf(
                        claims.get(JwtClaims.USER_TYPE, String.class));

                // 6. 인증된 사용자를 표현하는 커스텀 Principal 객체 생성
                CustomPrincipal principal = new CustomPrincipal(userId, userIdentifier, userType);

                // 7. 스프링 시큐리티의 Authentication 객체 (UsernamePasswordAuthenticationToken) 생성
                // 비밀번호 (Credentials)는 이미 JWT로 인증이 끝났으므로 null 처리
                // 사용자의 권한 (Role) 목록을 SimpleGrantedAuthority로 감싸서 전달
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                List.of(new SimpleGrantedAuthority(
                                        JwtClaims.ROLE_PREFIX + userType.name()))
                        );

                // 8. 최종적으로 SecurityContext에 인증 객체를 저장하여 전역에서 사용 가능하게 함
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException e) {
                // 토큰 만료, 서명 불일치, 형식 오류
                log.warn("JWT 검증 실패: {}", e.getMessage());
                SecurityContextHolder.clearContext(); // 안전을 위해 컨텍스트 초기화

                // JwtException 외 예외도 인증 실패로 동일 처리
                // Long.valueOf(null)   → NumberFormatException (IllegalArgumentException 하위)
                // UserType.valueOf(null) → IllegalArgumentException
                // claims.get() null 참조 → NullPointerException
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("JWT 클레임 파싱 실패 - 인증 거부: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 인증에 필요한 클레임이 모두 존재하고 유효한지 검증한다.
     *
     * [검증 항목]
     * - subject        : userId (null 또는 빈 문자열이면 유효하지 않음)
     * - userIdentifier : UUID 식별자 (null이면 유효하지 않음)
     * - userType       : 사용자 유형 (null이면 유효하지 않음)
     *
     * @param claims JWT 파싱 후 추출된 클레임
     * @return 모든 필수 클레임이 존재하면 true, 하나라도 누락이면 false
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
     * Authorization 헤더에서 Bearer 토큰을 추출한다.
     * 헤더가 없거나 형식이 맞지 않으면 null을 반환한다.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        // 헤더에 값이 있고, "Bearer "로 시작하는지 확인
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            // "Bearer " 이후의 문자열 (실제 토큰)만 잘라서 반환
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}