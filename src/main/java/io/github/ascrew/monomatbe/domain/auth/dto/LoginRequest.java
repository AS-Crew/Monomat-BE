package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 자체 로그인 요청 DTO
 *
 * [Validation message 정책]
 * message에는 사용자 표시 문구가 아니라 AuthErrorCode enum 이름을 넣는다.
 * 실제 사용자 표시 메시지는 AuthErrorCode에서 관리하고,
 * AuthExceptionHandler가 code/message/field 응답으로 변환한다.
 */
public record LoginRequest(
        @NotBlank(message = "AUTH_LOGIN_ID_REQUIRED")
        @Size(min = 4, max = 50, message = "AUTH_LOGIN_ID_INVALID_LENGTH")
        @Pattern(regexp = "^[A-Za-z0-9]+$", message = "AUTH_LOGIN_ID_INVALID_FORMAT")
        String loginId,

        @NotBlank(message = "AUTH_PASSWORD_REQUIRED")
        @Size(min = 8, max = 100, message = "AUTH_PASSWORD_INVALID_LENGTH")
        @Pattern(regexp = "^\\S+$", message = "AUTH_PASSWORD_CONTAINS_WHITESPACE")
        String password
) {
}