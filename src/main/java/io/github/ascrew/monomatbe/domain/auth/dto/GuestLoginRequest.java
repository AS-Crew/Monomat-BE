package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게스트 로그인 요청 DTO
 *
 * 클라이언트는 닉네임만 전달하고, 서버가 게스트 계정 생성/세션 발급을 담당한다.
 *
 * [Validation message 정책]
 * message에는 사용자 표시 문구가 아니라 AuthErrorCode enum 이름을 넣는다.
 * 실제 사용자 표시 메시지는 AuthErrorCode에서 관리하고,
 * AuthExceptionHandler가 code/message/field 응답으로 변환한다.
 */
public record GuestLoginRequest(
        @NotBlank(message = "AUTH_NICKNAME_REQUIRED")
        @Size(min = 2, max = 12, message = "AUTH_NICKNAME_INVALID_LENGTH")
        String nickname
) {
}