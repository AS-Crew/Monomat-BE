package io.github.ascrew.monomatbe.domain.youtube.model;

/**
 * YouTube 영상 검증 후 확보한 메타데이터
 *
 * @param durationSeconds 영상 길이(초). YouTube oEmbed는 duration을 제공하지 않으므로 null일 수 있습니다.
 */
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