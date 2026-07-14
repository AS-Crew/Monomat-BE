package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final Long USER_ID = 1L;

    private static final String USER_IDENTIFIER =
            "11111111-1111-1111-1111-111111111111";

    private static final String ACCESS_TOKEN =
            "access-token";

    @Mock
    private AccessTokenAuthenticator accessTokenAuthenticator;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtAuthenticationFilter =
                new JwtAuthenticationFilter(
                        accessTokenAuthenticator
                );

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 USER Access Token은 ROLE_USER로 인증된다")
    void validUserTokenAuthenticatesAsUser()
            throws ServletException, IOException {

        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenReturn(
                        authentication(UserRole.USER)
                );

        MockHttpServletRequest request =
                authenticatedRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        Authentication springAuthentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        assertThat(springAuthentication)
                .isNotNull();

        assertThat(springAuthentication.isAuthenticated())
                .isTrue();

        assertThat(springAuthentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");

        assertThat(springAuthentication.getPrincipal())
                .isInstanceOf(CustomPrincipal.class);

        CustomPrincipal principal =
                (CustomPrincipal) springAuthentication.getPrincipal();

        assertThat(principal.userId())
                .isEqualTo(USER_ID);

        assertThat(principal.userIdentifier())
                .isEqualTo(USER_IDENTIFIER);

        assertThat(principal.userType())
                .isEqualTo(UserType.REGISTERED);

        assertThat(principal.role())
                .isEqualTo(UserRole.USER);

        verify(filterChain)
                .doFilter(request, response);
    }

    @Test
    @DisplayName("정상 ADMIN Access Token은 ROLE_ADMIN으로 인증된다")
    void validAdminTokenAuthenticatesAsAdmin()
            throws ServletException, IOException {

        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenReturn(
                        authentication(UserRole.ADMIN)
                );

        MockHttpServletRequest request =
                authenticatedRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        Authentication springAuthentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        assertThat(springAuthentication)
                .isNotNull();

        assertThat(springAuthentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");

        CustomPrincipal principal =
                (CustomPrincipal) springAuthentication.getPrincipal();

        assertThat(principal.role())
                .isEqualTo(UserRole.ADMIN);

        verify(filterChain)
                .doFilter(request, response);
    }

    @Test
    @DisplayName("Access Token 인증 실패 시 SecurityContext를 비우고 필터 체인은 계속 진행한다")
    void authenticationFailureClearsContextAndContinues()
            throws ServletException, IOException {

        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenThrow(
                        new AccessTokenAuthenticationException(
                                AccessTokenFailureReason.INVALID
                        )
                );

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                "stale-principal",
                                null
                        )
                );

        MockHttpServletRequest request =
                authenticatedRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        assertThat(
                SecurityContextHolder.getContext()
                        .getAuthentication()
        ).isNull();

        verify(filterChain)
                .doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 인증을 시도하지 않고 필터 체인을 진행한다")
    void missingAuthorizationSkipsAuthentication()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        assertThat(
                SecurityContextHolder.getContext()
                        .getAuthentication()
        ).isNull();

        verifyNoInteractions(accessTokenAuthenticator);

        verify(filterChain)
                .doFilter(request, response);
    }

    @Test
    @DisplayName("Bearer 형식이 아니면 인증을 시도하지 않는다")
    void invalidAuthorizationSchemeSkipsAuthentication()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                ACCESS_TOKEN
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        verifyNoInteractions(accessTokenAuthenticator);

        verify(filterChain)
                .doFilter(request, response);
    }

    @Test
    @DisplayName("Bearer 뒤의 Token 값이 비어 있으면 인증을 시도하지 않는다")
    void blankBearerTokenSkipsAuthentication()
            throws ServletException, IOException {

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer "
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(
                request,
                response,
                filterChain
        );

        verifyNoInteractions(accessTokenAuthenticator);

        verify(filterChain)
                .doFilter(request, response);
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + ACCESS_TOKEN
        );

        return request;
    }

    private AccessTokenAuthentication authentication(
            UserRole userRole
    ) {
        return new AccessTokenAuthentication(
                USER_ID,
                USER_IDENTIFIER,
                UserType.REGISTERED,
                userRole
        );
    }
}