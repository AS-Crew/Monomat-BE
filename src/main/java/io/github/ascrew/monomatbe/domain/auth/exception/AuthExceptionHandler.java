package io.github.ascrew.monomatbe.domain.auth.exception;

import io.github.ascrew.monomatbe.domain.auth.dto.AuthErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 인증 API 전용 예외 핸들러
 *
 * [적용 범위]
 * - io.github.ascrew.monomatbe.domain.auth 패키지 하위 컨트롤러에서 발생한 예외만 처리한다.
 *
 * [설계 의도]
 * - global 패키지가 domain.auth를 직접 의존하지 않도록 인증 도메인 내부에 위치시킨다.
 * - 로비/맵/신고 등 다른 도메인의 기존 에러 응답 포맷에 영향을 주지 않는다.
 * - #128 범위에서는 인증 API의 에러 응답만 code/message/field 포맷으로 표준화한다.
 */
@Slf4j
@RestControllerAdvice(basePackages = AuthExceptionHandler.AUTH_BASE_PACKAGE)
public class AuthExceptionHandler {

    static final String AUTH_BASE_PACKAGE = "io.github.ascrew.monomatbe.domain.auth";

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
     * [주의]
     * DTO annotation message가 AuthErrorCode enum 이름과 일치하지 않으면
     * 5xx가 아니라 AUTH_INVALID_REQUEST_BODY(400)로 fallback하고 warn 로그를 남긴다.
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
     * JSON body 파싱 실패를 인증 API 표준 응답으로 변환한다.
     *
     * 잘못된 JSON은 서버 장애가 아니라 클라이언트 요청 오류이므로
     * 503이 아니라 400 BAD_REQUEST를 반환한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AuthErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        AuthErrorCode errorCode = AuthErrorCode.AUTH_INVALID_REQUEST_BODY;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AuthErrorResponse.from(errorCode));
    }

    private AuthErrorCode resolveAuthErrorCode(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            log.warn("Auth validation failed but field errors are empty");
            return AuthErrorCode.AUTH_INVALID_REQUEST_BODY;
        }

        return fieldErrors.stream()
                .map(this::resolveAuthErrorCode)
                .min(AuthValidationErrorPriority::compare)
                .orElse(AuthErrorCode.AUTH_INVALID_REQUEST_BODY);
    }

    private AuthErrorCode resolveAuthErrorCode(FieldError fieldError) {
        if (fieldError == null) {
            log.warn("Auth validation field error is null");
            return AuthErrorCode.AUTH_INVALID_REQUEST_BODY;
        }

        String field = fieldError.getField();
        Object rejectedValue = fieldError.getRejectedValue();

        if (isBlankValue(rejectedValue)) {
            return resolveRequiredErrorCode(field);
        }

        if (hasOnlyWhitespaceViolation(field, rejectedValue)) {
            return resolveWhitespaceErrorCode(field);
        }

        return resolveDefinedAuthErrorCode(fieldError);
    }

    private AuthErrorCode resolveDefinedAuthErrorCode(FieldError fieldError) {
        String rawCode = fieldError.getDefaultMessage();
        AuthErrorCode errorCode = AuthErrorCode.fromCode(rawCode);

        if (errorCode == AuthErrorCode.AUTH_INVALID_REQUEST_BODY) {
            log.warn(
                    "Unknown auth validation error code - field: {}, rejectedValue: {}, defaultMessage: {}",
                    fieldError.getField(),
                    fieldError.getRejectedValue(),
                    rawCode
            );
        }

        return errorCode;
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
            case FIELD_REFRESH_TOKEN -> AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED;
            default -> {
                log.warn("Unknown required auth field - field: {}", field);
                yield AuthErrorCode.AUTH_INVALID_REQUEST_BODY;
            }
        };
    }

    private AuthErrorCode resolveWhitespaceErrorCode(String field) {
        return switch (field) {
            case FIELD_LOGIN_ID -> AuthErrorCode.AUTH_LOGIN_ID_CONTAINS_WHITESPACE;
            case FIELD_PASSWORD -> AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE;
            default -> {
                log.warn("Unknown whitespace auth field - field: {}", field);
                yield AuthErrorCode.AUTH_INVALID_REQUEST_BODY;
            }
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
                     AUTH_REFRESH_TOKEN_REQUIRED -> 1;

                case AUTH_LOGIN_ID_CONTAINS_WHITESPACE,
                     AUTH_PASSWORD_CONTAINS_WHITESPACE -> 2;

                case AUTH_LOGIN_ID_INVALID_LENGTH,
                     AUTH_PASSWORD_INVALID_LENGTH,
                     AUTH_NICKNAME_INVALID_LENGTH -> 3;

                case AUTH_LOGIN_ID_INVALID_FORMAT -> 4;

                case AUTH_INVALID_REQUEST_BODY -> 90;

                default -> 100;
            };
        }
    }
}