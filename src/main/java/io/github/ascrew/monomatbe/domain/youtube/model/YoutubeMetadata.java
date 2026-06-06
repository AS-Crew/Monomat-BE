package io.github.ascrew.monomatbe.domain.youtube.model;

public record YoutubeMetadata(
        String videoId,
        String title,
        String artist,
        String thumbnailUrl,
        Integer durationSeconds
) {

    public YoutubeMetadata(
            String videoId,
            String title,
            String artist,
            String thumbnailUrl
    ) {
        this(videoId, title, artist, thumbnailUrl, null);
    }
}