package io.github.ascrew.monomatbe.domain.youtube;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeVideoIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "dQw4w9WgXcQ",  // 일반적인 11자 ID
            "abcde123456",  // 영문 소문자 + 숫자
            "_-_-_-_-_-_",  // 언더스코어/하이픈 허용
            "AAAAAAAAAAA"   // 영문 대문자만
    })
    void isValid_returnsTrueFor11CharAllowedChars(String videoId) {
        assertThat(YoutubeVideoId.isValid(videoId)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "abcdefg",        // 11자 미만
            "abcdefghijklm",  // 11자 초과
            "dQw4w9WgXc!",    // 허용되지 않는 문자(!)
            "dQw4 9WgXcQ",    // 공백 포함
            "한글영상아이디일일"   // 비ASCII
    })
    void isValid_returnsFalseForInvalidFormat(String videoId) {
        assertThat(YoutubeVideoId.isValid(videoId)).isFalse();
    }
}
