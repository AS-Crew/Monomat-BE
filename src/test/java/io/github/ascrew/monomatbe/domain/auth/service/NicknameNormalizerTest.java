package io.github.ascrew.monomatbe.domain.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NicknameNormalizerTest {

    private final NicknameNormalizer nicknameNormalizer = new NicknameNormalizer();

    @Test
    @DisplayName("비교용 정규화 시 앞뒤 공백, 대소문자, 내부 공백을 제거한다")
    void normalizeForComparison() {
        String result = nicknameNormalizer.normalizeForComparison(" A d M i n ");

        assertEquals("admin", result);
    }

    @Test
    @DisplayName("한글 금칙어도 내부 공백을 제거해 정규화한다")
    void normalizeKoreanWord() {
        String result = nicknameNormalizer.normalizeForComparison(" 관 리 자 ");

        assertEquals("관리자", result);
    }

    @Test
    @DisplayName("탭과 줄바꿈도 공백으로 판단해 제거한다")
    void normalizeWhitespaceCharacters() {
        String result = nicknameNormalizer.normalizeForComparison("A\tD\nM I N");

        assertEquals("admin", result);
    }

    @Test
    @DisplayName("null 입력은 빈 문자열로 정규화한다")
    void normalizeNull() {
        String result = nicknameNormalizer.normalizeForComparison(null);

        assertEquals("", result);
    }

    @Test
    @DisplayName("공백만 있는 입력은 빈 문자열로 정규화한다")
    void normalizeBlank() {
        String result = nicknameNormalizer.normalizeForComparison("   \t\n  ");

        assertEquals("", result);
    }
}