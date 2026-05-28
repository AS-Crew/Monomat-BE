package io.github.ascrew.monomatbe.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 금칙어 생성 요청 DTO
 */
public record ForbiddenNicknameCreateRequest(

        @NotBlank(message = "금칙어는 비어 있을 수 없습니다.")
        @Size(max = 100, message = "금칙어는 100자 이하로 입력해주세요.")
        String word
) {
}