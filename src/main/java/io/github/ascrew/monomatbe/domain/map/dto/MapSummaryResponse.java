package io.github.ascrew.monomatbe.domain.map.dto;

import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import lombok.Builder;

@Builder
public record MapSummaryResponse(
        Long mapId,
        String title,
        MapCategory category,
        int numOfSong,
        int totalPlayTime,
        boolean isPublic,
        boolean pendingPublic,
        Long ownerId
) {
}
