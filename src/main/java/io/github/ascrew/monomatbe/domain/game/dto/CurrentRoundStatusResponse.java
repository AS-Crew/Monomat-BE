package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

@Builder
public record CurrentRoundStatusResponse(
        int roundNo,
        String status,
        String roundPhase,
        int timeLimitSeconds,
        Long serverStartedAt,
        String videoId,
        String youtubeUrl,
        Integer startTime,
        Integer endTime,
        Integer remainingSeconds,
        boolean isCorrect
) {
}

