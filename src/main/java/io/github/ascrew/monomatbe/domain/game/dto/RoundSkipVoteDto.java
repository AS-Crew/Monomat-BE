package io.github.ascrew.monomatbe.domain.game.dto;

import io.github.ascrew.monomatbe.global.constant.GameEventTypes;
import lombok.Builder;

/**
 * 라운드 스킵 투표 현황 브로드캐스트 DTO.
 */
@Builder
public record RoundSkipVoteDto(
        String type,
        int roundNo,
        long votes,
        long requiredVotes,
        long totalParticipants
) {
    public RoundSkipVoteDto {
        if (type == null || !GameEventTypes.ROUND_SKIP_VOTE.equals(type)) {
            type = GameEventTypes.ROUND_SKIP_VOTE;
        }
    }
}
