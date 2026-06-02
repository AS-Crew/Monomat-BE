package io.github.ascrew.monomatbe.domain.auth.exception;

import org.springframework.http.HttpStatus;

import java.util.Arrays;

/**
 * 인증 도메인 전용 에러 코드
 *
 * [설계 의도]
 * - 프론트엔드는 message 문자열이 아니라 code와 field를 기준으로 에러 UI를 제어한다.
 * - 사용자 표시 메시지는 서버에서 관리하되, FE 분기 기준은 안정적인 code 값으로 고정한다.
 * - field가 null인 에러는 특정 입력 필드가 아니라 인증/세션 전체 상태와 관련된 에러다.
 */
public enum AuthErrorCode {

    // =========================================================
    // Request
    // =========================================================

    AUTH_INVALID_REQUEST_BODY(
            HttpStatus.BAD_REQUEST,
            "요청 본문 형식이 올바르지 않습니다.",
            null
    ),

    // =========================================================
    // Login ID
    // =========================================================

    AUTH_LOGIN_ID_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "로그인 ID를 입력해주세요.",
            AuthErrorFields.LOGIN_ID
    ),

    AUTH_LOGIN_ID_INVALID_LENGTH(
            HttpStatus.BAD_REQUEST,
            "로그인 ID는 4자 이상 50자 이하로 입력해주세요.",
            AuthErrorFields.LOGIN_ID
    ),

    AUTH_LOGIN_ID_INVALID_FORMAT(
            HttpStatus.BAD_REQUEST,
            "로그인 ID는 영문과 숫자만 사용할 수 있습니다.",
            AuthErrorFields.LOGIN_ID
    ),

    AUTH_LOGIN_ID_CONTAINS_WHITESPACE(
            HttpStatus.BAD_REQUEST,
            "로그인 ID에는 공백을 포함할 수 없습니다.",
            AuthErrorFields.LOGIN_ID
    ),

    AUTH_LOGIN_ID_DUPLICATED(
            HttpStatus.CONFLICT,
            "이미 사용 중인 로그인 ID입니다.",
            AuthErrorFields.LOGIN_ID
    ),

    // =========================================================
    // Password
    // =========================================================

    AUTH_PASSWORD_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "비밀번호는 비어 있을 수 없습니다.",
            AuthErrorFields.PASSWORD
    ),

    AUTH_PASSWORD_INVALID_LENGTH(
            HttpStatus.BAD_REQUEST,
            "비밀번호는 8자 이상 100자 이하여야 합니다.",
            AuthErrorFields.PASSWORD
    ),

    AUTH_PASSWORD_CONTAINS_WHITESPACE(
            HttpStatus.BAD_REQUEST,
            "비밀번호에는 공백을 포함할 수 없습니다.",
            AuthErrorFields.PASSWORD
    ),

    AUTH_CURRENT_PASSWORD_MISMATCH(
            HttpStatus.UNAUTHORIZED,
            "현재 비밀번호가 올바르지 않습니다.",
            AuthErrorFields.CURRENT_PASSWORD
    ),

    AUTH_NEW_PASSWORD_CONFIRM_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "새 비밀번호가 일치하지 않습니다.",
            AuthErrorFields.NEW_PASSWORD_CONFIRM
    ),

    AUTH_NEW_PASSWORD_SAME_AS_CURRENT(
            HttpStatus.BAD_REQUEST,
            "새 비밀번호는 현재 비밀번호와 달라야 합니다.",
            AuthErrorFields.NEW_PASSWORD
    ),

    // =========================================================
    // Nickname
    // =========================================================

    AUTH_NICKNAME_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "닉네임은 비어 있을 수 없습니다.",
            AuthErrorFields.NICKNAME
    ),

    AUTH_NICKNAME_INVALID_LENGTH(
            HttpStatus.BAD_REQUEST,
            "닉네임은 2자 이상 12자 이하로 입력해주세요.",
            AuthErrorFields.NICKNAME
    ),

    AUTH_NICKNAME_DUPLICATED(
            HttpStatus.CONFLICT,
            "이미 사용 중인 닉네임입니다.",
            AuthErrorFields.NICKNAME
    ),

    AUTH_NICKNAME_FORBIDDEN_WORD(
            HttpStatus.BAD_REQUEST,
            "금칙어가 포함된 닉네임은 사용할 수 없습니다.",
            AuthErrorFields.NICKNAME
    ),

    // =========================================================
    // Forbidden Nickname Admin
    // =========================================================

    AUTH_FORBIDDEN_NICKNAME_WORD_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "금칙어는 비어 있을 수 없습니다.",
            "word"
    ),

    AUTH_FORBIDDEN_NICKNAME_WORD_DUPLICATED(
            HttpStatus.CONFLICT,
            "이미 등록된 금칙어입니다.",
            "word"
    ),

    AUTH_FORBIDDEN_NICKNAME_WORD_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "존재하지 않는 금칙어입니다.",
            null
    ),

    AUTH_FORBIDDEN_NICKNAME_CACHE_EVICT_FAILED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "금칙어 캐시 갱신에 실패했습니다. 잠시 후 다시 시도해주세요.",
            null
    ),

    // =========================================================
    // Login / Session
    // =========================================================

    AUTH_INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
            null
    ),

    AUTH_ACCOUNT_LOCKED(
            HttpStatus.LOCKED,
            "로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요.",
            null
    ),

    AUTH_UNAUTHENTICATED(
            HttpStatus.UNAUTHORIZED,
            "인증 정보가 없습니다.",
            null
    ),

    AUTH_INVALID_AUTHORIZATION(
            HttpStatus.UNAUTHORIZED,
            "Authorization 헤더가 유효하지 않습니다.",
            null
    ),

    AUTH_REFRESH_TOKEN_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "Refresh Token은 비어 있을 수 없습니다.",
            AuthErrorFields.REFRESH_TOKEN
    ),

    AUTH_INVALID_REFRESH_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "Refresh Token이 유효하지 않습니다.",
            null
    ),

    AUTH_SESSION_EXPIRED(
            HttpStatus.UNAUTHORIZED,
            "세션이 만료되었습니다.",
            null
    ),

    AUTH_REGISTERED_USER_ONLY(
            HttpStatus.FORBIDDEN,
            "정식 회원만 사용할 수 있는 기능입니다.",
            null
    ),

    AUTH_TEMPORARY_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
            null
    );

    private final HttpStatus httpStatus;
    private final String message;
    private final String field;

    AuthErrorCode(HttpStatus httpStatus, String message, String field) {
        this.httpStatus = httpStatus;
        this.message = message;
        this.field = field;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public String getField() {
        return field;
    }

    public static AuthErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return AUTH_INVALID_REQUEST_BODY;
        }

        return Arrays.stream(values())
                .filter(errorCode -> errorCode.name().equals(code))
                .findFirst()
                .orElse(AUTH_INVALID_REQUEST_BODY);
    }

    /**
     * 인증 API field 이름 상수
     *
     * DTO field 이름과 프론트엔드 form field 이름을 동일하게 유지하기 위해 문자열을 한 곳에서만 관리한다.
     */
    private static final class AuthErrorFields {

        private static final String LOGIN_ID = "loginId";
        private static final String PASSWORD = "password";
        private static final String CURRENT_PASSWORD = "currentPassword";
        private static final String NEW_PASSWORD = "newPassword";
        private static final String NEW_PASSWORD_CONFIRM = "newPasswordConfirm";
        private static final String NICKNAME = "nickname";
        private static final String REFRESH_TOKEN = "refreshToken";

        private AuthErrorFields() {
        }
    }
}