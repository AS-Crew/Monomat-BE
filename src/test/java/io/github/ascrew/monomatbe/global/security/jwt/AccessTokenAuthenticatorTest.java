package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenAuthenticatorTest {

    private static final String ACCESS_TOKEN =
            "access-token";

    private static final Long USER_ID = 1L;

    private static final String USER_IDENTIFIER =
            "11111111-1111-1111-1111-111111111111";

    private static final String SESSION_ID =
            "22222222-2222-2222-2222-222222222222";

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private StringRedisTemplate redisTemplate;

    private AccessTokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        authenticator = new AccessTokenAuthenticator(
                jwtTokenProvider,
                redisTemplate
        );
    }

    @Test
    @DisplayName("유효한 Access Token은 인증 정보를 반환한다")
    void validAccessTokenReturnsAuthentication() {
        Claims claims = validAccessClaims();

        givenParsedClaims(claims);
        givenTokenNotBlacklisted();
        givenActiveSession(true);

        AccessTokenAuthentication result =
                authenticator.authenticate(ACCESS_TOKEN);

        assertThat(result.userId())
                .isEqualTo(USER_ID);

        assertThat(result.userIdentifier())
                .isEqualTo(USER_IDENTIFIER);

        assertThat(result.userType())
                .isEqualTo(UserType.REGISTERED);

        assertThat(result.userRole())
                .isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("빈 Access Token은 INVALID로 거부한다")
    void blankAccessTokenIsRejected() {
        assertFailureReason(
                " ",
                AccessTokenFailureReason.INVALID
        );

        verifyNoInteractions(
                jwtTokenProvider,
                redisTemplate
        );
    }

    @Test
    @DisplayName("만료된 JWT는 EXPIRED로 구분한다")
    void expiredTokenIsRejected() {
        ExpiredJwtException expiredJwtException =
                mock(ExpiredJwtException.class);

        when(jwtTokenProvider.parseClaims(ACCESS_TOKEN))
                .thenThrow(expiredJwtException);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.EXPIRED
        );
    }

    @Test
    @DisplayName("형식이 잘못된 JWT는 INVALID로 구분한다")
    void malformedTokenIsRejected() {
        when(jwtTokenProvider.parseClaims(ACCESS_TOKEN))
                .thenThrow(
                        new MalformedJwtException(
                                "invalid token"
                        )
                );

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    @Test
    @DisplayName("Refresh Token은 Access Token 인증에 사용할 수 없다")
    void refreshTokenTypeIsRejected() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.TOKEN_TYPE,
                        JwtTokenType.REFRESH.name(),

                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name(),

                        JwtClaims.SESSION_ID,
                        SESSION_ID
                )
        );

        givenParsedClaims(claims);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    @Test
    @DisplayName("알 수 없는 tokenType은 INVALID로 거부한다")
    void unknownTokenTypeIsRejected() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.TOKEN_TYPE,
                        "UNKNOWN",

                        JwtClaims.USER_IDENTIFIER,
                        USER_IDENTIFIER,

                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name()
                )
        );

        givenParsedClaims(claims);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    @Test
    @DisplayName("userIdentifier가 없는 Access Token은 INVALID로 거부한다")
    void missingUserIdentifierIsRejected() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.TOKEN_TYPE,
                        JwtTokenType.ACCESS.name(),

                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name(),

                        JwtClaims.USER_ROLE,
                        UserRole.USER.name()
                )
        );

        givenParsedClaims(claims);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    @Test
    @DisplayName("userType이 없는 Access Token은 INVALID로 거부한다")
    void missingUserTypeIsRejected() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.TOKEN_TYPE,
                        JwtTokenType.ACCESS.name(),

                        JwtClaims.USER_IDENTIFIER,
                        USER_IDENTIFIER,

                        JwtClaims.USER_ROLE,
                        UserRole.USER.name()
                )
        );

        givenParsedClaims(claims);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    @Test
    @DisplayName("잘못된 userRole 값은 INVALID로 거부한다")
    void invalidUserRoleIsRejected() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.TOKEN_TYPE,
                        JwtTokenType.ACCESS.name(),

                        JwtClaims.USER_IDENTIFIER,
                        USER_IDENTIFIER,

                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name(),

                        JwtClaims.USER_ROLE,
                        "SUPER_ADMIN"
                )
        );

        givenParsedClaims(claims);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    @Test
    @DisplayName("블랙리스트 Access Token은 REVOKED로 거부한다")
    void blacklistedTokenIsRejected() {
        Claims claims = validAccessClaims();

        givenParsedClaims(claims);

        when(redisTemplate.hasKey(
                RedisKeys.accessTokenBlacklistKey(
                        TokenHashUtils.sha256(ACCESS_TOKEN)
                )
        )).thenReturn(true);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.REVOKED
        );
    }

    @Test
    @DisplayName("활성 인증 세션이 없으면 REVOKED로 거부한다")
    void inactiveSessionIsRejected() {
        Claims claims = validAccessClaims();

        givenParsedClaims(claims);
        givenTokenNotBlacklisted();
        givenActiveSession(false);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.REVOKED
        );
    }

    @Test
    @DisplayName("블랙리스트 Redis 조회 실패는 AUTHENTICATION_UNAVAILABLE로 거부한다")
    void blacklistRedisFailureIsFailClosed() {
        Claims claims = validAccessClaims();

        givenParsedClaims(claims);

        when(redisTemplate.hasKey(
                RedisKeys.accessTokenBlacklistKey(
                        TokenHashUtils.sha256(ACCESS_TOKEN)
                )
        )).thenThrow(
                new IllegalStateException(
                        "redis unavailable"
                )
        );

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.AUTHENTICATION_UNAVAILABLE
        );
    }

    @Test
    @DisplayName("활성 세션 Redis 조회 실패는 AUTHENTICATION_UNAVAILABLE로 거부한다")
    void activeSessionRedisFailureIsFailClosed() {
        Claims claims = validAccessClaims();

        givenParsedClaims(claims);
        givenTokenNotBlacklisted();

        when(redisTemplate.hasKey(
                RedisKeys.activeSessionKey(USER_IDENTIFIER)
        )).thenThrow(
                new IllegalStateException(
                        "redis unavailable"
                )
        );

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.AUTHENTICATION_UNAVAILABLE
        );
    }

    @Test
    @DisplayName("tokenType이 없는 기존 Access Token은 임시 호환한다")
    void legacyAccessTokenIsAccepted() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.USER_IDENTIFIER,
                        USER_IDENTIFIER,

                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name()
                )
        );

        givenParsedClaims(claims);
        givenTokenNotBlacklisted();
        givenActiveSession(true);

        AccessTokenAuthentication result =
                authenticator.authenticate(ACCESS_TOKEN);

        assertThat(result.userId())
                .isEqualTo(USER_ID);

        assertThat(result.userIdentifier())
                .isEqualTo(USER_IDENTIFIER);

        /*
         * userRole Claim 도입 이전 토큰은 USER 권한으로만 호환한다.
         */
        assertThat(result.userRole())
                .isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("tokenType이 없는 기존 Refresh Token은 Access Token으로 호환하지 않는다")
    void legacyRefreshTokenIsRejected() {
        Claims claims = claims(
                Map.of(
                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name(),

                        JwtClaims.SESSION_ID,
                        SESSION_ID
                )
        );

        givenParsedClaims(claims);

        assertFailureReason(
                ACCESS_TOKEN,
                AccessTokenFailureReason.INVALID
        );
    }

    private Claims validAccessClaims() {
        return claims(
                Map.of(
                        JwtClaims.TOKEN_TYPE,
                        JwtTokenType.ACCESS.name(),

                        JwtClaims.USER_IDENTIFIER,
                        USER_IDENTIFIER,

                        JwtClaims.USER_TYPE,
                        UserType.REGISTERED.name(),

                        JwtClaims.USER_ROLE,
                        UserRole.USER.name()
                )
        );
    }

    private Claims claims(Map<String, Object> values) {
        return Jwts.claims()
                .subject(String.valueOf(USER_ID))
                .add(values)
                .build();
    }

    private void givenParsedClaims(Claims claims) {
        when(jwtTokenProvider.parseClaims(ACCESS_TOKEN))
                .thenReturn(claims);
    }

    private void givenTokenNotBlacklisted() {
        when(redisTemplate.hasKey(
                RedisKeys.accessTokenBlacklistKey(
                        TokenHashUtils.sha256(ACCESS_TOKEN)
                )
        )).thenReturn(false);
    }

    private void givenActiveSession(boolean active) {
        when(redisTemplate.hasKey(
                RedisKeys.activeSessionKey(USER_IDENTIFIER)
        )).thenReturn(active);
    }

    private void assertFailureReason(
            String token,
            AccessTokenFailureReason expectedReason
    ) {
        assertThatThrownBy(
                () -> authenticator.authenticate(token)
        )
                .isInstanceOf(
                        AccessTokenAuthenticationException.class
                )
                .extracting(
                        exception ->
                                ((AccessTokenAuthenticationException) exception)
                                        .getReason()
                )
                .isEqualTo(expectedReason);
    }
}