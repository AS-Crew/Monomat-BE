package io.github.ascrew.monomatbe.domain.auth.dto;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import lombok.Builder;

/**
 * 회원가입 응답 DTO.
 * (#34 범위에서는 계정 생성 결과만 반환하며 토큰은 발급하지 않습니다.)
 */
@Builder
public record RegisterResponse(
        Long userId,
        String loginId,
        String nickname,
        UserType userType
) {
}
