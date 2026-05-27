package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh Token 재발급 요청 DTO.
 *
 * [Validation message 정책]
 * message에는 사용자 표시 문구가 아니라 AuthErrorCode enum 이름을 넣는다.
 * 실제 사용자 표시 메시지는 AuthErrorCode에서 관리하고,
 * AuthExceptionHandler가 code/message/field 응답으로 변환한다.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "AUTH_INVALID_REFRESH_TOKEN")
        String refreshToken
) {
}