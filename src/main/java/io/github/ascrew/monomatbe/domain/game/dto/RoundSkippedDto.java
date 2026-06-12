package io.github.ascrew.monomatbe.domain.game.dto;

import io.github.ascrew.monomatbe.global.constant.GameEventTypes;
import lombok.Builder;

/**
 * 스킵 계열 경로로 라운드 종료 락을 획득했음을 알리는 DTO.
 */
@Builder
public record RoundSkippedDto(
        String type,
        int roundNo,
        RoundEndReason endReason
) {
    public RoundSkippedDto {
        if (type == null || !GameEventTypes.ROUND_SKIPPED.equals(type)) {
            type = GameEventTypes.ROUND_SKIPPED;
        }
    }
}
