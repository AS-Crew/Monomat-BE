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
        String password,

        /**
         * 강제 로그인 여부
         *
         * [정책]
         * 회원 계정은 동시에 한 기기에서만 로그인할 수 있다.
         * 이미 활성 세션이 있는 경우 기본 로그인(force=false)은 AUTH_CONCURRENT_LOGIN_REJECTED(409)로 거부된다.
         * FE가 사용자에게 안내한 뒤 force=true로 재요청하면 기존 세션을 강제 종료하고 로그인한다.
         *
         * [기본값]
         * JSON 본문에 force 필드가 없으면 false로 처리된다.
         */
        boolean force
) {
}