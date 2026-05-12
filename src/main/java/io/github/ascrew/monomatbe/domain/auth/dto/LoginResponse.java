package io.github.ascrew.monomatbe.domain.auth.dto;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import lombok.Builder;

import java.time.Instant;

/**
 * 자체 로그인 응답 DTO.
 */
@Builder
public record LoginResponse(
        Long userId,
        String loginId,
        String nickname,
        UserType userType,
        String userIdentifier,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {
}
