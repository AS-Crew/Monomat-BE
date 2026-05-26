package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

@Builder
public record RoundPlaybackStartedDto(
        String type,
        int roundNo,
        long serverStartedAt,
        int durationSeconds
) {
}
