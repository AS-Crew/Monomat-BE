package io.github.ascrew.monomatbe.domain.game.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LevenshteinDistanceTest {

    @Test
    @DisplayName("Levenshtein Distance 기본 계산 검증")
    void calculateTest() {
        // 완전 일치
        assertThat(LevenshteinDistance.calculate("abc", "abc")).isZero();

        // 1 문자 삽입/삭제/대체
        assertThat(LevenshteinDistance.calculate("abc", "ab")).isEqualTo(1); // 삭제
        assertThat(LevenshteinDistance.calculate("abc", "abcd")).isEqualTo(1); // 삽입
        assertThat(LevenshteinDistance.calculate("abc", "axc")).isEqualTo(1); // 대체

        // 다중 변경
        assertThat(LevenshteinDistance.calculate("kitten", "sitting")).isEqualTo(3);
        assertThat(LevenshteinDistance.calculate("한글테스트", "한글텍스트")).isEqualTo(1);
        assertThat(LevenshteinDistance.calculate("가나다라마", "가마바사")).isEqualTo(4);
    }

    @Test
    @DisplayName("Null 입력 시 예외 발생 검증")
    void calculateNullValidation() {
        assertThatThrownBy(() -> LevenshteinDistance.calculate(null, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LevenshteinDistance.calculate("abc", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
