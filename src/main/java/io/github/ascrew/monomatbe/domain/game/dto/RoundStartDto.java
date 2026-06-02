package io.github.ascrew.monomatbe.domain.game.dto;

import io.github.ascrew.monomatbe.global.constant.GameEventTypes;
import lombok.Builder;

/**
 * 인게임 라운드 시작 정보를 클라이언트(FE)에 브로드캐스트하기 위한 DTO.
 * 정답, 제목, 아티스트 정보는 절대 포함하지 않고, IFrame 재생을 위한 최소 정보만 포함한다.
 */
@Builder
public record RoundStartDto(
        String type,
        String videoId,
        String youtubeUrl,
        int startTime,
        int endTime,
        int timeLimitSeconds,
        int roundNo,
        long serverStartedAt
) {
    public RoundStartDto {
        if (type == null) {
            type = GameEventTypes.ROUND_READY;
        }
    }
}
