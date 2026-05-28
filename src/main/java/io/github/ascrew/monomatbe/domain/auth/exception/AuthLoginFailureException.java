package io.github.ascrew.monomatbe.domain.auth.exception;

/**
 * 로그인 실패 상태 변경을 커밋해야 하는 인증 예외
 *
 * [사용 범위]
 * - 비밀번호 불일치로 failedLoginCount를 증가시킨 뒤 예외를 던지는 경우
 * - failedLoginCount가 임계값에 도달해 lockedUntil을 설정한 뒤 예외를 던지는 경우
 *
 * [주의]
 * 모든 AuthException을 noRollbackFor로 처리하면 향후 login() 내부에서
 * DB write 이후 다른 인증 예외가 발생했을 때 의도하지 않은 부분 커밋이 발생할 수 있다.
 * 따라서 트랜잭션 롤백 제외 범위는 로그인 실패 상태 변경을 커밋해야 하는
 * 이 예외 타입으로만 제한한다.
 */
public class AuthLoginFailureException extends AuthException {

    public AuthLoginFailureException(AuthErrorCode errorCode) {
        super(errorCode);
    }

    public AuthLoginFailureException(AuthErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}