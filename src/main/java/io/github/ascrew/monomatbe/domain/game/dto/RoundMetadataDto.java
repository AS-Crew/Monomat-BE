package io.github.ascrew.monomatbe.domain.game.dto;

import io.github.ascrew.monomatbe.global.constant.GameEventTypes;
import lombok.Builder;
import java.util.List;

/**
 * 라운드 종료 시 클라이언트(FE)에 공개할 메타데이터 DTO.
 */
@Builder
public record RoundMetadataDto(
        String type,
        String title,
        String artist,
        String answer,
        String thumbnailUrl,
        List<PlayerRankingDto> rankings,
        int waitTimeSeconds,
        boolean isLastRound
) {
    public RoundMetadataDto {
        if (type == null) {
            type = GameEventTypes.ROUND_END;
        }
    }
}
