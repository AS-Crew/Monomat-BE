package io.github.ascrew.monomatbe.domain.youtube.service;

public record YoutubeMetadata(
        String videoId,
        String title,
        String artist,
        String thumbnailUrl
) {
}
