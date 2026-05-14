package io.github.ascrew.monomatbe.domain.auth.dto;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import lombok.Builder;

import java.time.Instant;

@Builder
public record RefreshTokenResponse(
        Long userId,
        UserType userType,
        String userIdentifier,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {
}
