package io.github.ascrew.monomatbe.domain.map.support;

public final class HintTextGenerator {

    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };
    private static final char HANGUL_BASE = 0xAC00;
    private static final char HANGUL_LAST = 0xD7A3;
    private static final int CHOSEONG_INTERVAL = 588;

    private HintTextGenerator() {}

    public static String toInitialConsonants(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch >= HANGUL_BASE && ch <= HANGUL_LAST) {
                int index = (ch - HANGUL_BASE) / CHOSEONG_INTERVAL;
                builder.append(CHOSEONG[index]);
                continue;
            }

            if (Character.isWhitespace(ch)) {
                builder.append(' ');
                continue;
            }

            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
            }
        }

        return builder.toString().trim();
    }
}
