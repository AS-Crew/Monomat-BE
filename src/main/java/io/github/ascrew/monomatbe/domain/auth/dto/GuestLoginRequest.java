package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게스트 로그인 요청 DTO.
 *
 * 클라이언트는 닉네임만 전달하고,
 * 서버가 게스트 계정 생성/세션 발급을 담당합니다.
 */
public record GuestLoginRequest(
        @NotBlank(message = "닉네임은 비어 있을 수 없습니다.")
        @Size(max = 8, message = "닉네임은 8자를 초과할 수 없습니다.")
        String nickname
) {
}
