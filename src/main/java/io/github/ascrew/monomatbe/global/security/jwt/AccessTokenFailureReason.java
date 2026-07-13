package io.github.ascrew.monomatbe.global.security.jwt;

/**
 * Access Token 인증 실패 원인을 구분한다.
 * 보안 계층은 WebSocket 전용 StompErrorCode에 의존하지 않는다.
 * REST와 STOMP는 이 값을 각 프로토콜에 맞는 응답으로 변환한다.
 */
public enum AccessTokenFailureReason {

    /**
     * 서명, 형식, Claim 또는 토큰 유형이 유효하지 않다.
     */
    INVALID,

    /**
     * Access Token의 만료 시간이 지났다.
     */
    EXPIRED,

    /**
     * 토큰이 블랙리스트에 있거나 활성 세션이 폐기되었다.
     */
    REVOKED,

    /**
     * Redis 장애 등으로 인증 상태를 신뢰할 수 없다.
     * 인증은 fail-closed 방식으로 거부한다.
     */
    AUTHENTICATION_UNAVAILABLE
}