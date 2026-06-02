package io.github.ascrew.monomatbe.domain.auth.exception;

/**
 * 비밀번호 변경 과정에서 현재 비밀번호 검증 실패를 나타내는 예외
 *
 * [설계 의도]
 * - 현재 비밀번호 불일치 시 failedLoginCount / lockedUntil 변경은 커밋되어야 한다.
 * - 일반 AuthException은 트랜잭션 롤백 대상이지만,
 *   이 예외는 UserCommandService.changeMyPassword(...)의 noRollbackFor 대상이다.
 * - 로그인 실패(AuthLoginFailureException)와 동일한 보안 정책을 비밀번호 변경 흐름에도 적용한다.
 */
public class AuthPasswordChangeFailureException extends AuthException {

    public AuthPasswordChangeFailureException(AuthErrorCode errorCode) {
        super(errorCode);
    }
}