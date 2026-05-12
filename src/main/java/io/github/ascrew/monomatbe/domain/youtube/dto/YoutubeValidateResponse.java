package io.github.ascrew.monomatbe.domain.youtube.dto;

import lombok.Builder;

@Builder
public record YoutubeValidateResponse(
        String videoId,
        String title,
        String artist,
        String thumbnailUrl
) {
}
