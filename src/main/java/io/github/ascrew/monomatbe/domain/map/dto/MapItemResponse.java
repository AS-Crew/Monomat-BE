package io.github.ascrew.monomatbe.domain.map.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record MapItemResponse(
        Long id,
        Long mapId,
        int orderNum,
        String youtubeUrl,
        String videoId,
        int startTime,
        int endTime,
        String title,
        String artist,
        String thumbnailUrl,
        List<String> answers,
        String hint,
        int hintTime,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
