package io.github.ascrew.monomatbe.domain.user.dto;

import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 로그인한 사용자의 기본 정보 조회 응답 DTO
 *
 * FE 상단 프로필, 닉네임 표시, 게스트/회원 분기 처리에 필요한 최소 사용자 정보를 제공한다.
 */
@Builder
public record MyUserInfoResponse(
        Long userId,
        String username,
        UserType userType,
        UserStatus status,
        LocalDateTime createdAt
) {
}