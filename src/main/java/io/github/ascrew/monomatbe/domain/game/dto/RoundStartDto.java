package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

/**
 * 인게임 라운드 시작 정보를 클라이언트(FE)에 브로드캐스트하기 위한 DTO.
 * 정답, 제목, 아티스트 정보는 절대 포함하지 않고, IFrame 재생을 위한 최소 정보만 포함한다.
 */
@Builder
public record RoundStartDto(
        String videoId,
        int startTime,
        int endTime,
        int roundNo,
        long serverTime
) {
}
