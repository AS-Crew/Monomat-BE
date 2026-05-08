package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 DTO.
 */
public record RegisterRequest(
        @NotBlank(message = "로그인 ID는 비어 있을 수 없습니다.")
        @Size(max = 50, message = "로그인 ID는 50자를 초과할 수 없습니다.")
        String loginId,

        @NotBlank(message = "비밀번호는 비어 있을 수 없습니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하여야 합니다.")
        String password,

        @NotBlank(message = "닉네임은 비어 있을 수 없습니다.")
        @Size(max = 50, message = "닉네임은 50자를 초과할 수 없습니다.")
        String nickname
) {
}
