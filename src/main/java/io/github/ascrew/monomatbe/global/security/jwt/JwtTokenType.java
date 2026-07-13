package io.github.ascrew.monomatbe.global.security.jwt;

/**
 * JWT의 사용 목적을 구분한다.
 *
 * Access Token과 Refresh Token은 동일한 서명 키를 사용하므로,
 * 토큰 Claim을 기준으로 인증 용도를 명확하게 구분한다.
 */
public enum JwtTokenType {

    /**
     * REST API와 STOMP CONNECT 인증에 사용하는 단기 토큰.
     */
    ACCESS,

    /**
     * Access Token 재발급에만 사용하는 장기 토큰.
     */
    REFRESH
}