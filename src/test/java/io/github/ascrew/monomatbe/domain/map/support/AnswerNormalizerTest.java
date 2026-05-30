package io.github.ascrew.monomatbe.domain.map.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerNormalizerTest {

    @Test
    void normalize_removesAllWhitespaceTrimAndInternal() {
        assertThat(AnswerNormalizer.normalize("데이 식스")).isEqualTo("데이식스");
        assertThat(AnswerNormalizer.normalize("  Bruno Mars ")).isEqualTo("brunomars");
    }

    @Test
    void normalize_lowercasesAscii() {
        assertThat(AnswerNormalizer.normalize("IU")).isEqualTo("iu");
        assertThat(AnswerNormalizer.normalize("BTS")).isEqualTo("bts");
    }

    @Test
    void normalize_removesCommas() {
        assertThat(AnswerNormalizer.normalize("a,b,c")).isEqualTo("abc");
        assertThat(AnswerNormalizer.normalize("가，나")).isEqualTo("가나");
    }

    @Test
    void normalize_removesFullWidthAndNonBreakingSpaces() {
        assertThat(AnswerNormalizer.normalize("브루노　마스")).isEqualTo("브루노마스");
        assertThat(AnswerNormalizer.normalize("브루노 마스")).isEqualTo("브루노마스");
    }

    @Test
    void normalize_appliesNfc() {
        // 분해형(NFD) 한글 자모가 조합형(NFC)으로 통일되어야 한다.
        String nfd = "가"; // ㄱ + ㅏ → "가"
        String decomposed = new String(new int[]{0x1100, 0x1161}, 0, 2); // 초성 ㄱ + 중성 ㅏ
        String composed = new String(Character.toChars(0xAC00)); // U+AC00 "가"
        assertThat(AnswerNormalizer.normalize(decomposed)).isEqualTo(composed);
    }

    @Test
    void normalize_nullOrBlankBecomesEmpty() {
        assertThat(AnswerNormalizer.normalize(null)).isEmpty();
        assertThat(AnswerNormalizer.normalize("   ")).isEmpty();
        assertThat(AnswerNormalizer.normalize(",")).isEmpty();
    }

    @Test
    void normalize_isIdempotent() {
        String once = AnswerNormalizer.normalize("  Bruno Mars ");
        assertThat(AnswerNormalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    void normalizeList_dedupsAfterNormalizationPreservingOrder() {
        List<String> result = AnswerNormalizer.normalizeList(List.of("IU", "i u"));
        assertThat(result).containsExactly("iu");
    }

    @Test
    void normalizeList_preservesFirstOccurrenceOrder() {
        List<String> result = AnswerNormalizer.normalizeList(List.of("좋은날", "Good Day", "좋은 날"));
        // "좋은날"과 "좋은 날"은 dedup, "Good Day"→"goodday". 첫 등장 순서 보존.
        assertThat(result).containsExactly("좋은날", "goodday");
    }

    @Test
    void normalizeList_dropsBlankAndNullElements() {
        List<String> result = AnswerNormalizer.normalizeList(Arrays.asList("정답", "  ", null, ","));
        assertThat(result).containsExactly("정답");
    }

    @Test
    void normalizeList_nullInputReturnsEmpty() {
        assertThat(AnswerNormalizer.normalizeList(null)).isEmpty();
    }
}
