package io.github.ascrew.monomatbe.global.exception;

import io.github.ascrew.monomatbe.domain.auth.controller.AuthController;
import io.github.ascrew.monomatbe.domain.auth.dto.AuthErrorResponse;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 인증 API 전용 예외 핸들러
 *
 * [적용 범위]
 * - AuthController에서 발생한 예외만 처리한다.
 *
 * [설계 의도]
 * - 로비/맵/신고 등 다른 도메인의 기존 에러 응답 포맷에 영향을 주지 않는다.
 * - #128 범위에서는 인증 API의 에러 응답만 code/message/field 포맷으로 표준화한다.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    /**
     * 인증 도메인 전용 예외를 표준 응답으로 변환한다.
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthErrorResponse> handleAuthException(AuthException exception) {
        AuthErrorCode errorCode = exception.getErrorCode();

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }

    /**
     * @Valid 검증 실패를 인증 에러 코드 응답으로 변환한다.
     *
     * [중요]
     * DTO validation message에는 AuthErrorCode enum 이름을 넣는 방식으로 매핑한다.
     *
     * 예:
     * @NotBlank(message = "AUTH_LOGIN_ID_REQUIRED")
     *
     * 이렇게 해야 message 문자열 변경과 FE 분기 코드가 분리된다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        AuthErrorCode errorCode = resolveAuthErrorCode(exception);

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }

    private AuthErrorCode resolveAuthErrorCode(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .orElse(null);

        if (fieldError == null) {
            return AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE;
        }

        return AuthErrorCode.fromCode(fieldError.getDefaultMessage());
    }
}