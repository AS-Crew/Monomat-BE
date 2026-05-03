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
 * 4. CustomPrincipal 생성 후 SecurityContext 저장
 *
 * [토큰 없는 요청 처리]
 * permitAll 경로는 토큰 없이 통과시킨다.
 * 경로별 접근 제어는 SecurityConfig의 authorizeHttpRequests에서 담당한다.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // JWT가 포함된 HTTP 헤더의 이름과 토큰의 접두사 상수 정의
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    // JWT 서명 검증을 위한 암호화 키
    private final SecretKey secretKey;

    /**
     * 필터 생성자
     * application.yml 등의 설정 파일에서 JWT 시크릿 키를 주입받아 SecretKey 객체로 초기화한다.
     *
     * @param secret 설정 파일에 정의된 auth.jwt.secret 값
     */
    public JwtAuthenticationFilter(@Value("${auth.jwt.secret}") String secret) {
        // 주입받은 문자열 형태의 시크릿 키를 UTF-8 바이트 배열로 반환
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // JJWT 라이브러리를 사용하여 HMAC SHA 알고리즘에 적합한 암호화 키 객체 (SecretKey) 생성
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 실제 필터링 로직이 수행되는 메서드
     * HTTP 요청이 들어올 때마다 토큰의 유효성을 검사하고, 유효한 경우 인증 정보를 설정한다.
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. 요청 헤더에서 JWT 토큰 문자열만 추출
        String token = extractToken(request);

        // 토큰이 존재하는 경우에만 검증 로직 수행 (토큰이 없다면 바로 다음 필터로 이동)
        if (token != null) {
            try {
                // 2. 토큰 파싱 및 서명 (Signature) 검증
                Claims claims = Jwts.parser()
                        .verifyWith(secretKey) // 서버가 가진 시크릿 키로 서명이 변조되지 않았는지 검증
                        .build()
                        .parseSignedClaims(token) // 토큰을 파싱하여 클레임 (데이터) 객체 반환 (만료된 경우 여기서 예외 발생)
                        .getPayload(); // 검증에 성공하면 토큰의 Payload (클레임 내용)를 가져옴

                // 3. Payload에서 비즈니스 로직에 필요한 사용자 정보 추출
                // JWT의 Subject에는 주로 사용자의 식별자 (PK)를 저장하므로 Long 타입으로 변환하여 가져옴
                Long userId = Long.valueOf(claims.getSubject());

                // JwtClaims 상수로 클레임 키 참조 (JwtTokenProvider 발급 시와 동일한 키)
                // JwtClaims 상수로 정의된 키를 사용하여 커스텀 클레임 추출 (토큰 발급 시 넣었던 데이터)
                String userIdentifier = claims.get(JwtClaims.USER_IDENTIFIER, String.class);
                UserType userType = UserType.valueOf(claims.get(JwtClaims.USER_TYPE, String.class));

                // 4. 추출한 정보는 Spring Security 환경에서 사용할 인증 주체 (Principal) 객체 생성
                CustomPrincipal principal = new CustomPrincipal(userId, userIdentifier, userType);

                // 5. Spring Security의 인증 토큰 객체 생성
                // Principal : 사용자 정보, Credentials : 비밀번호 (이미 인증되었으므로 null), Authorities : 권한 목록)
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                // Spring Security의 Role 기반 인가를 위해 "ROLE_" 접두사를 붙여 권한 부여
                                List.of(new SimpleGrantedAuthority(
                                        JwtClaims.ROLE_PREFIX + userType.name()))
                        );

                // 6. SecurityContext (보안 컨텍스트)에 생성한 인증 정보 객체 저장
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException e) {
                // 토큰 만료, 서명 불일치, 형식 오류 등의 JWT 예외 발생 시 처리
                log.warn("JWT 검증 실패: {}", e.getMessage());
                // 잘못된 토큰으로 인한 보안 위협을 막기 위해 Context를 완전히 초기화 (비움)
                SecurityContextHolder.clearContext();
            }
        }

        // 7. 현재 필터의 작업이 끝났으므로 다음 필터 (혹은 서블릿)로 요청 전달
        // 토큰이 없거나, 검증에 실패했더라도 permitAll로 설정된 엔드포인트일 수 있으므로 일단 다음으로 넘김
        filterChain.doFilter(request, response);
    }

    /**
     * HTTP Request의 Authorization 헤더에서 Bearer 토큰 문자열만 잘라내어 추출하는 헬퍼 메서드
     * Authorization 헤더에서 Bearer 토큰을 추출한다.
     * 헤더가 없거나 형식이 맞지 않으면 null을 반환한다.
     */
    private String extractToken(HttpServletRequest request) {
        // "Authorization" 헤더의 값을 가져옴
        String header = request.getHeader(HEADER_AUTHORIZATION);

        // 헤더 값이 존재하고, "Bearer "로 시작하는지 확인 (대소문자/띄어쓰기 주의)
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            // "Bearer " 이후의 실제 토큰 값만 잘라서 반환
            return header.substring(BEARER_PREFIX.length());
        }
        return null; // 조건에 맞지 않으면 토큰이 없는 것으로 간주
    }
}