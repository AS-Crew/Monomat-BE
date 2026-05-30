package io.github.ascrew.monomatbe.domain.game.support;

import io.github.ascrew.monomatbe.domain.map.support.AnswerNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzyMatcherTest {

    @Test
    @DisplayName("정답 후보 길이에 따른 허용 임계 거리(Threshold) 반환 테스트")
    void getThresholdTest() {
        // 1~2글자: 0
        assertThat(FuzzyMatcher.getThreshold(1)).isZero();
        assertThat(FuzzyMatcher.getThreshold(2)).isZero();

        // 3~5글자: 1
        assertThat(FuzzyMatcher.getThreshold(3)).isEqualTo(1);
        assertThat(FuzzyMatcher.getThreshold(5)).isEqualTo(1);

        // 6~9글자: 2
        assertThat(FuzzyMatcher.getThreshold(6)).isEqualTo(2);
        assertThat(FuzzyMatcher.getThreshold(9)).isEqualTo(2);

        // 10글자 이상: 3
        assertThat(FuzzyMatcher.getThreshold(10)).isEqualTo(3);
        assertThat(FuzzyMatcher.getThreshold(15)).isEqualTo(3);
    }

    private boolean isMatchNormalized(String answer, String target) {
        return FuzzyMatcher.isMatch(AnswerNormalizer.normalize(answer), AnswerNormalizer.normalize(target));
    }

    @Test
    @DisplayName("FuzzyMatch 판별 성공 및 실패 케이스 테스트")
    void isMatchTest() {
        // 1. 짧은 글자 (1~2글자) -> 오타 허용 없음
        assertThat(isMatchNormalized("가나", "가나")).isTrue();
        assertThat(isMatchNormalized("가다", "가나")).isFalse();

        // 2. 중간 글자 (3~5글자, 임계치 1) -> 1글자 차이 허용
        assertThat(isMatchNormalized("bts", "bts")).isTrue();
        assertThat(isMatchNormalized("bts", "bta")).isTrue(); // 3글자, 1글자 차이 허용
        assertThat(isMatchNormalized("bts", "baa")).isFalse(); // 2글자 차이 불가

        // 3. 긴 글자 (6~9글자, 임계치 2) -> 2글자 차이 허용
        assertThat(isMatchNormalized("다이너마이트", "다이너마이트")).isTrue();
        assertThat(isMatchNormalized("다이너마이트", "다이너마이")).isTrue(); // 1글자 삭제 허용
        assertThat(isMatchNormalized("다이너마이트", "다이나마이그")).isTrue(); // 2글자 변경 허용 (다이'나'마이'그')
        assertThat(isMatchNormalized("다이너마이트", "다이아바이그")).isFalse(); // 3글자 변경 불가 (너->아, 마->바, 트->그)

        // 4. 매우 긴 글자 (10글자 이상, 임계치 3)
        String target = "permissiontodance"; // 17글자
        assertThat(isMatchNormalized("permissiontodance", "permissiontodance")).isTrue();
        assertThat(isMatchNormalized("permissiontodanc", "permissiontodance")).isTrue(); // 1글자 차이
        assertThat(isMatchNormalized("permisiontodanse", "permissiontodance")).isTrue(); // 2글자 차이 (m 하나, c->s)
        assertThat(isMatchNormalized("permisontodanse", "permissiontodance")).isTrue(); // 3글자 차이 (m 하나, i 누락, c->s)
        assertThat(isMatchNormalized("permisontodanseee", "permissiontodance")).isFalse(); // 4글자 차이 불가
    }
}

