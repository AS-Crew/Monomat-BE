package io.github.ascrew.monomatbe.domain.map.dto;

import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record MapDetailResponse(
        Long id,
        Long ownerId,
        String title,
        String description,
        MapCategory category,
        int numOfSong,
        int totalPlayTime,
        boolean isPublic,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
