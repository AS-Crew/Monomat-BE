package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final String SECRET =
            "01234567890123456789012345678901";
    private static final Long USER_ID = 1L;
    private static final String USER_IDENTIFIER =
            "11111111-1111-1111-1111-111111111111";

    private StringRedisTemplate redisTemplate;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        jwtAuthenticationFilter = new JwtAuthenticationFilter(
                SECRET,
                redisTemplate
        );

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("userRole claim이 없는 기존 Access Token은 ROLE_USER로 인증된다")
    void doFilterInternal_withoutUserRoleClaim_authenticatesAsUser()
            throws ServletException, IOException {
        // given
        String token = createAccessTokenWithoutUserRole();

        when(redisTemplate.hasKey(RedisKeys.accessTokenBlacklistKey(TokenHashUtils.sha256(token))))
                .thenReturn(false);
        when(redisTemplate.hasKey(RedisKeys.activeSessionKey(USER_IDENTIFIER)))
                .thenReturn(true);

        MockHttpServletRequest request = authenticatedRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");

        assertThat(authentication.getPrincipal())
                .isInstanceOf(CustomPrincipal.class);

        CustomPrincipal principal = (CustomPrincipal) authentication.getPrincipal();

        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.userIdentifier()).isEqualTo(USER_IDENTIFIER);
        assertThat(principal.userType()).isEqualTo(UserType.REGISTERED);
        assertThat(principal.role()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("userRole=ADMIN Access Token은 ROLE_ADMIN으로 인증된다")
    void doFilterInternal_withAdminUserRoleClaim_authenticatesAsAdmin()
            throws ServletException, IOException {
        // given
        String token = createAccessTokenWithUserRole(UserRole.ADMIN.name());

        when(redisTemplate.hasKey(RedisKeys.accessTokenBlacklistKey(TokenHashUtils.sha256(token))))
                .thenReturn(false);
        when(redisTemplate.hasKey(RedisKeys.activeSessionKey(USER_IDENTIFIER)))
                .thenReturn(true);

        MockHttpServletRequest request = authenticatedRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");

        CustomPrincipal principal = (CustomPrincipal) authentication.getPrincipal();

        assertThat(principal.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("userRole claim 값이 잘못되면 인증되지 않는다")
    void doFilterInternal_invalidUserRoleClaim_doesNotAuthenticate()
            throws ServletException, IOException {
        // given
        String token = createAccessTokenWithUserRole("SUPER_ADMIN");

        when(redisTemplate.hasKey(RedisKeys.accessTokenBlacklistKey(TokenHashUtils.sha256(token))))
                .thenReturn(false);

        MockHttpServletRequest request = authenticatedRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        assertThat(authentication).isNull();
    }

    @Test
    @DisplayName("active session이 Redis에 없으면 인증되지 않는다")
    void doFilterInternal_inactiveSession_doesNotAuthenticate()
            throws ServletException, IOException {
        // given
        String token = createAccessTokenWithUserRole(UserRole.USER.name());

        when(redisTemplate.hasKey(RedisKeys.accessTokenBlacklistKey(TokenHashUtils.sha256(token))))
                .thenReturn(false);
        when(redisTemplate.hasKey(RedisKeys.activeSessionKey(USER_IDENTIFIER)))
                .thenReturn(false);

        MockHttpServletRequest request = authenticatedRequest(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        // then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        assertThat(authentication).isNull();
    }

    private MockHttpServletRequest authenticatedRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private String createAccessTokenWithoutUserRole() {
        return createToken(Map.of(
                JwtClaims.USER_IDENTIFIER, USER_IDENTIFIER,
                JwtClaims.USER_TYPE, UserType.REGISTERED.name()
        ));
    }

    private String createAccessTokenWithUserRole(String userRole) {
        return createToken(Map.of(
                JwtClaims.USER_IDENTIFIER, USER_IDENTIFIER,
                JwtClaims.USER_TYPE, UserType.REGISTERED.name(),
                JwtClaims.USER_ROLE, userRole
        ));
    }

    private String createToken(Map<String, Object> claims) {
        Instant now = Instant.now();
        SecretKey secretKey = Keys.hmacShaKeyFor(
                SECRET.getBytes(StandardCharsets.UTF_8)
        );

        return Jwts.builder()
                .subject(String.valueOf(USER_ID))
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(secretKey)
                .compact();
    }
}