package io.github.ascrew.monomatbe.domain.game.dto;

import lombok.Builder;

@Builder
public record PlayerRankingDto(
        String userIdentifier,
        String nickname,
        int score,
        int rank,
        int scoreAdded
) {}
