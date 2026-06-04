package io.github.ascrew.monomatbe.domain.user.exception;

import io.github.ascrew.monomatbe.domain.auth.dto.AuthErrorResponse;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 사용자 API에서 발생하는 인증 도메인 예외를 표준 에러 응답으로 변환한다.
 *
 * [설계 의도]
 * - UserController는 정식 회원 닉네임/비밀번호 변경 과정에서 AuthException 계열 예외를 사용할 수 있다.
 * - 기존 AuthExceptionHandler는 domain.auth 컨트롤러에만 적용되므로 domain.user 컨트롤러의 AuthException은 처리하지 못한다.
 * - user 도메인 DTO의 @Valid 메시지 정책은 기존 AuthExceptionHandler와 다르므로,
 *   이 핸들러에서는 AuthException 계열만 처리하여 검증 예외 처리 부작용을 피한다.
 */
@Slf4j
@RestControllerAdvice(basePackages = UserAuthExceptionHandler.USER_BASE_PACKAGE)
public class UserAuthExceptionHandler {

    static final String USER_BASE_PACKAGE = "io.github.ascrew.monomatbe.domain.user";

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthErrorResponse> handleAuthException(AuthException exception) {
        AuthErrorCode errorCode = exception.getErrorCode();

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }
}