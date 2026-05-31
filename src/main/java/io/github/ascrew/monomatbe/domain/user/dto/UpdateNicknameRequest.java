package io.github.ascrew.monomatbe.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 정식 회원 닉네임 변경 요청 DTO
 *
 * @param username 변경할 닉네임
 */
public record UpdateNicknameRequest(

        @NotBlank(message = "닉네임을 입력해주세요.")
        @Size(max = 50, message = "닉네임은 50자 이하로 입력해주세요.")
        String username
) {
}