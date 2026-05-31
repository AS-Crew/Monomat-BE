package io.github.ascrew.monomatbe.domain.map.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerNormalizerTest {

    @Test
    @DisplayName("유튜브 메타데이터 정제 테스트 (cleanMetadata)")
    void cleanMetadataTest() {
        // Given
        String raw1 = "Dynamite [MV]";
        String raw2 = "Butter (Official Video)";
        String raw3 = "Permission To Dance (Official Music Video)";
        String raw4 = "Life Goes On mv";
        String raw5 = "Stay Gold [Official]";

        // When & Then
        assertThat(AnswerNormalizer.cleanMetadata(raw1)).isEqualTo("Dynamite");
        assertThat(AnswerNormalizer.cleanMetadata(raw2)).isEqualTo("Butter");
        assertThat(AnswerNormalizer.cleanMetadata(raw3)).isEqualTo("Permission To Dance");
        assertThat(AnswerNormalizer.cleanMetadata(raw4)).isEqualTo("Life Goes On");
        assertThat(AnswerNormalizer.cleanMetadata(raw5)).isEqualTo("Stay Gold");
    }

    @Test
    @DisplayName("단일 정답 문자열 전체 정규화 테스트 (normalize)")
    void normalizeTest() {
        // Given
        String raw1 = " Dynamite [MV] ";
        String raw2 = "Butter, (Official Video)";
        String raw3 = "가나다라， 마바사";

        // When & Then
        // 1. Dynamite [MV] -> Dynamite -> dynamite (공백제거 및 소문자화)
        assertThat(AnswerNormalizer.normalize(raw1)).isEqualTo("dynamite");

        // 2. Butter, (Official Video) -> Butter, -> butter (쉼표제거, 공백제거)
        assertThat(AnswerNormalizer.normalize(raw2)).isEqualTo("butter");

        // 3. 가나다라， 마바사 -> 가나다라마바사 (전각쉼표제거, 공백제거)
        assertThat(AnswerNormalizer.normalize(raw3)).isEqualTo("가나다라마바사");
    }

    @Test
    @DisplayName("정답 리스트 전체 정규화 및 중복제거 테스트 (normalizeList)")
    void normalizeListTest() {
        // Given
        List<String> rawList = List.of(
                "Dynamite [MV]",
                "dynamite",
                " Butter (Official) ",
                "butter",
                ""
        );

        // When
        List<String> result = AnswerNormalizer.normalizeList(rawList);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo("dynamite");
        assertThat(result.get(1)).isEqualTo("butter");
    }
}
