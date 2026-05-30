package io.github.ascrew.monomatbe.domain.auth.exception;

/**
 * 인증 도메인 전용 예외
 *
 * AuthException은 AuthErrorCode를 강제하여
 * 모든 인증 실패 응답이 code/message/field 계약을 따르도록 한다.
 */
public class AuthException extends RuntimeException {

    private final AuthErrorCode errorCode;

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AuthException(AuthErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public AuthErrorCode getErrorCode() {
        return errorCode;
    }
}