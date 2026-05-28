package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh Token 재발급 요청 DTO
 *
 * [Validation message 정책]
 * message에는 사용자 표시 문구가 아니라 AuthErrorCode enum 이름을 넣는다.
 * 실제 사용자 표시 메시지는 AuthErrorCode에서 관리하고,
 * AuthExceptionHandler가 code/message/field 응답으로 변환한다.
 *
 * [에러 정책]
 * - refreshToken 필드 누락/빈 값: AUTH_REFRESH_TOKEN_REQUIRED, 400
 * - refreshToken 형식 오류/불일치/위조: AUTH_INVALID_REFRESH_TOKEN, 401
 */
public record RefreshTokenRequest(
        @NotBlank(message = "AUTH_REFRESH_TOKEN_REQUIRED")
        String refreshToken
) {
}