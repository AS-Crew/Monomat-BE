package io.github.ascrew.monomatbe.global.exception;

import io.github.ascrew.monomatbe.domain.auth.controller.AuthController;
import io.github.ascrew.monomatbe.domain.auth.dto.AuthErrorResponse;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 인증 API 전용 예외 핸들러.
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

    private static final String FIELD_LOGIN_ID = "loginId";
    private static final String FIELD_PASSWORD = "password";
    private static final String FIELD_NICKNAME = "nickname";
    private static final String FIELD_REFRESH_TOKEN = "refreshToken";

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
     * [우선순위]
     * 1. 빈 값(null, blank)은 REQUIRED 계열로 처리
     * 2. 로그인 ID/비밀번호의 순수 공백 포함은 CONTAINS_WHITESPACE로 처리
     * 3. 그 외에는 DTO annotation message에 지정된 AuthErrorCode를 사용
     *
     * [이유]
     * 하나의 값이 여러 제약을 동시에 위반할 수 있다.
     * 예: loginId="" 는 @NotBlank와 @Size를 동시에 위반한다.
     * Spring Validation의 FieldError 순서에 의존하면 REQUIRED 대신 INVALID_LENGTH가 내려갈 수 있으므로,
     * 인증 API에서는 서버가 명시적으로 우선순위를 결정한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        AuthErrorCode errorCode = resolveAuthErrorCode(
                exception.getBindingResult().getFieldErrors()
        );

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }

    /**
     * Authorization 헤더 누락을 인증 API 표준 응답으로 변환한다.
     *
     * 로그아웃처럼 Authorization 헤더가 필요한 API에서
     * 컨트롤러 진입 전에 Spring MVC가 MissingRequestHeaderException을 던지는 경우를 방어한다.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<AuthErrorResponse> handleMissingRequestHeaderException(
            MissingRequestHeaderException exception
    ) {
        AuthErrorCode errorCode = AuthErrorCode.AUTH_INVALID_AUTHORIZATION;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }

    /**
     * JSON body 파싱 실패를 인증 API 표준 응답으로 변환한다.
     *
     * 현재 #128 권장 에러 코드에 요청 본문 형식 오류 전용 코드가 없으므로
     * 기존 AUTH_TEMPORARY_UNAVAILABLE을 사용한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AuthErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        AuthErrorCode errorCode = AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }

    private AuthErrorCode resolveAuthErrorCode(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE;
        }

        return fieldErrors.stream()
                .map(this::resolveAuthErrorCode)
                .min(AuthValidationErrorPriority::compare)
                .orElse(AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE);
    }

    private AuthErrorCode resolveAuthErrorCode(FieldError fieldError) {
        if (fieldError == null) {
            return AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE;
        }

        String field = fieldError.getField();
        Object rejectedValue = fieldError.getRejectedValue();

        if (isBlankValue(rejectedValue)) {
            return resolveRequiredErrorCode(field);
        }

        if (hasOnlyWhitespaceViolation(field, rejectedValue)) {
            return resolveWhitespaceErrorCode(field);
        }

        return AuthErrorCode.fromCode(fieldError.getDefaultMessage());
    }

    private boolean isBlankValue(Object rejectedValue) {
        if (rejectedValue == null) {
            return true;
        }

        if (rejectedValue instanceof String value) {
            return value.isBlank();
        }

        return false;
    }

    /**
     * 순수 공백 위반만 CONTAINS_WHITESPACE로 분리한다.
     *
     * 예:
     * - "test id"  -> whitespace 위반
     * - "test id!" -> 특수문자도 포함하므로 invalid format
     * - "test!"    -> invalid format
     */
    private boolean hasOnlyWhitespaceViolation(String field, Object rejectedValue) {
        if (!(rejectedValue instanceof String value)) {
            return false;
        }

        if (!FIELD_LOGIN_ID.equals(field) && !FIELD_PASSWORD.equals(field)) {
            return false;
        }

        if (value.chars().noneMatch(Character::isWhitespace)) {
            return false;
        }

        if (FIELD_PASSWORD.equals(field)) {
            return true;
        }

        String valueWithoutWhitespace = value.replaceAll("\\s+", "");
        return valueWithoutWhitespace.matches("^[A-Za-z0-9]+$");
    }

    private AuthErrorCode resolveRequiredErrorCode(String field) {
        return switch (field) {
            case FIELD_LOGIN_ID -> AuthErrorCode.AUTH_LOGIN_ID_REQUIRED;
            case FIELD_PASSWORD -> AuthErrorCode.AUTH_PASSWORD_REQUIRED;
            case FIELD_NICKNAME -> AuthErrorCode.AUTH_NICKNAME_REQUIRED;
            case FIELD_REFRESH_TOKEN -> AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN;
            default -> AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE;
        };
    }

    private AuthErrorCode resolveWhitespaceErrorCode(String field) {
        return switch (field) {
            case FIELD_LOGIN_ID -> AuthErrorCode.AUTH_LOGIN_ID_CONTAINS_WHITESPACE;
            case FIELD_PASSWORD -> AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE;
            default -> AuthErrorCode.AUTH_TEMPORARY_UNAVAILABLE;
        };
    }

    /**
     * 여러 FieldError가 동시에 발생했을 때 사용자에게 가장 정확한 에러를 먼저 반환하기 위한 우선순위.
     */
    private static final class AuthValidationErrorPriority {

        private AuthValidationErrorPriority() {
        }

        private static int compare(AuthErrorCode left, AuthErrorCode right) {
            return Integer.compare(priority(left), priority(right));
        }

        private static int priority(AuthErrorCode errorCode) {
            return switch (errorCode) {
                case AUTH_LOGIN_ID_REQUIRED,
                     AUTH_PASSWORD_REQUIRED,
                     AUTH_NICKNAME_REQUIRED,
                     AUTH_INVALID_REFRESH_TOKEN -> 1;

                case AUTH_LOGIN_ID_CONTAINS_WHITESPACE,
                     AUTH_PASSWORD_CONTAINS_WHITESPACE -> 2;

                case AUTH_LOGIN_ID_INVALID_LENGTH,
                     AUTH_PASSWORD_INVALID_LENGTH,
                     AUTH_NICKNAME_INVALID_LENGTH -> 3;

                case AUTH_LOGIN_ID_INVALID_FORMAT -> 4;

                default -> 100;
            };
        }
    }
}