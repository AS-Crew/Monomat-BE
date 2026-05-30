package io.github.ascrew.monomatbe.domain.map.support;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 정답 정규화 유틸
 *
 * [정책] 정답은 저장·비교 모두 동일한 정규화를 거친다.
 *   - 유튜브 메타데이터 제거 (괄호 내용 [MV], (Official) 등 및 official, video, mv 등의 키워드 제거)
 *   - 유니코드 NFC 통일 (한글 조합형/호환 문자 차이 제거)
 *   - 모든 공백 제거 (일반 공백 + 비분리 공백 U+00A0 + 전각 공백 U+3000 등)
 *   - 쉼표 제거 (ASCII ',' + 전각 '，')
 *   - 소문자화
 *
 * [설계 의도]
 *   - 저장값 = 표시값 = 비교값이 모두 동일하도록 단일 유틸로 공유한다.
 *   - 멱등(idempotent)하므로 FE가 선제 정제한 값에 다시 적용해도 무해하며,
 *     직접 API 호출 등 FE를 우회하는 경로에서도 BE가 데이터 무결성을 보장한다.
 */
public final class AnswerNormalizer {

    private static final Pattern METADATA_PATTERN = Pattern.compile(
            "\\[[^\\]]*\\]|\\([^\\)]*\\)|(?i)(official\\s+video|official\\s+audio|official\\s+mv|official\\s+music\\s+video|official|mv|audio)",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private AnswerNormalizer() {}

    /**
     * 원본 문자열에서 대괄호/소괄호 안의 메타데이터 및 YouTube 관련 키워드를 제거하고 공백을 정돈합니다.
     */
    public static String cleanMetadata(String raw) {
        if (raw == null) {
            return "";
        }
        return METADATA_PATTERN.matcher(raw).replaceAll("").trim();
    }

    /**
     * 단일 정답 문자열을 정규화한다.
     *
     * @param raw 원본 정답 (null 허용)
     * @return 정규화된 정답. 정규화 결과가 비면 빈 문자열을 반환한다.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String cleaned = cleanMetadata(raw);
        String nfc = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        StringBuilder builder = new StringBuilder(nfc.length());
        nfc.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                return;
            }
            if (cp == ',' || cp == '，') {
                return;
            }
            builder.appendCodePoint(Character.toLowerCase(cp));
        });
        return builder.toString();
    }

    /**
     * 정답 목록을 정규화한다. 각 원소를 정규화하고, 빈 값을 제거한 뒤,
     * 등장 순서를 보존하며 중복을 제거한다. 따라서 결과의 [0]번은 항상 결정적인 대표 정답이다.
     *
     * @param raw 원본 정답 목록 (null 허용)
     * @return 정규화·dedup된 정답 목록 (수정 불가)
     */
    public static List<String> normalizeList(List<String> raw) {
        if (raw == null) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
