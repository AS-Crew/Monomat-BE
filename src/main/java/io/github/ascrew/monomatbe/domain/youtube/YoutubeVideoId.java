package io.github.ascrew.monomatbe.domain.youtube;

import java.util.regex.Pattern;

/**
 * YouTube 영상 ID 형식 규칙의 단일 출처.
 * 영상 ID 유효성 판단이 필요한 모든 곳(URL 검증, 맵 공개 전 검증 등)은 이 클래스를 통해 일관된 규칙을 사용한다.
 */
public final class YoutubeVideoId {

    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private YoutubeVideoId() {}

    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }
}
