package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 자체 로그인 요청 DTO
 *
 * [Validation message 정책]
 * message에는 사용자 표시 문구가 아니라 AuthErrorCode enum 이름을 넣는다.
 *
 * [주의]
 * 로그인 API에서는 기존 회원 호환성을 위해 loginId 포맷 검증을 수행하지 않는다.
 * 포맷이 신규 정책과 다르더라도 DB 조회 후 인증 실패 시 AUTH_INVALID_CREDENTIALS로 처리한다.
 */
public record LoginRequest(
        @NotBlank(message = "AUTH_LOGIN_ID_REQUIRED")
        String loginId,

        @NotBlank(message = "AUTH_PASSWORD_REQUIRED")
        String password
) {
}