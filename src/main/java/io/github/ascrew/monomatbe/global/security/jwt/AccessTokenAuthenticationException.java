package io.github.ascrew.monomatbe.global.security.jwt;

/**
 * Access Token 인증 실패를 표현하는 공통 예외
 *
 * 토큰 원문이나 내부 JWT 오류 메시지를 외부에 직접 노출하지 않고,
 * 실패 원인만 상위 계층에 전달한다.
 */
public class AccessTokenAuthenticationException extends RuntimeException {

    private final AccessTokenFailureReason reason;

    public AccessTokenAuthenticationException(
            AccessTokenFailureReason reason
    ) {
        this(reason, null);
    }

    public AccessTokenAuthenticationException(
            AccessTokenFailureReason reason,
            Throwable cause
    ) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public AccessTokenFailureReason getReason() {
        return reason;
    }
}