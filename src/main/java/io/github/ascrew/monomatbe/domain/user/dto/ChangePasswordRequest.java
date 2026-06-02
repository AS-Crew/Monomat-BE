package io.github.ascrew.monomatbe.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 정식 회원 비밀번호 변경 요청 DTO
 *
 * [검증 책임 분리]
 * - DTO는 필드 존재 여부만 1차 검증한다.
 * - 비밀번호 길이, 공백 포함 여부 등 실제 정책 검증은 PasswordPolicyValidator에서 수행한다.
 * - 새 비밀번호 확인 일치 여부는 UserCommandService에서 명시적으로 검증한다.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "현재 비밀번호를 입력해주세요.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호를 입력해주세요.")
        String newPassword,

        @NotBlank(message = "새 비밀번호 확인을 입력해주세요.")
        String newPasswordConfirm
) {
}