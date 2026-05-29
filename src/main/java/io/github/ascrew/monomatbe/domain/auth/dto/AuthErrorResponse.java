package io.github.ascrew.monomatbe.domain.auth.dto;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;

/**
 * 인증 API 에러 응답 DTO
 *
 * [응답 예시]
 * {
 *   "code": "AUTH_INVALID_CREDENTIALS",
 *   "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
 *   "field": null
 * }
 *
 * [주의]
 * httpStatus는 HTTP status line에 이미 포함되므로 body에는 포함하지 않는다.
 * 프론트엔드는 code와 field를 기준으로 분기하고, message는 사용자 표시용으로만 사용한다.
 */
public record AuthErrorResponse(
        String code,
        String message,
        String field
) {

    public static AuthErrorResponse from(AuthErrorCode errorCode) {
        return new AuthErrorResponse(
                errorCode.name(),
                errorCode.getMessage(),
                errorCode.getField()
        );
    }
}