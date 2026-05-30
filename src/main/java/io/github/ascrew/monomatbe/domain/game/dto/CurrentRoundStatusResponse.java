package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

@Builder
public record CurrentRoundStatusResponse(
        int roundNo,
        String status,
        int timeLimitSeconds,
        Long serverStartedAt
) {
}
