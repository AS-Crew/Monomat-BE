package io.github.ascrew.monomatbe.domain.game.support;

/**
 * 두 문자열 간의 편집 거리(Levenshtein Distance)를 계산하는 유틸리티 클래스.
 * DP 2차원 배열을 1차원 배열로 압축하여 가상 스레드 환경에서 메모리 및 GC 부하를 최소화합니다.
 */
public final class LevenshteinDistance {

    private LevenshteinDistance() {}

    /**
     * s1과 s2 사이의 Levenshtein Distance를 계산합니다.
     *
     * @param s1 비교할 문자열 1 (null 불가)
     * @param s2 비교할 문자열 2 (null 불가)
     * @return 두 문자열 간의 최소 편집 횟수
     */
    public static int calculate(String s1, String s2) {
        if (s1 == null || s2 == null) {
            throw new IllegalArgumentException("문자열은 null일 수 없습니다.");
        }
        if (s1.equals(s2)) {
            return 0;
        }

        int len1 = s1.length();
        int len2 = s2.length();

        if (len1 == 0) return len2;
        if (len2 == 0) return len1;

        // 짧은 쪽을 열(columns)로 삼아 메모리 공간 사용을 min(len1, len2)로 줄임
        String shorter = len1 < len2 ? s1 : s2;
        String longer = len1 < len2 ? s2 : s1;

        int[] dp = new int[shorter.length() + 1];
        for (int i = 0; i <= shorter.length(); i++) {
            dp[i] = i;
        }

        for (int i = 1; i <= longer.length(); i++) {
            int previousDiagonal = dp[0];
            dp[0] = i;
            for (int j = 1; j <= shorter.length(); j++) {
                int temp = dp[j];
                if (longer.charAt(i - 1) == shorter.charAt(j - 1)) {
                    dp[j] = previousDiagonal;
                } else {
                    dp[j] = Math.min(Math.min(dp[j - 1], dp[j]), previousDiagonal) + 1;
                }
                previousDiagonal = temp;
            }
        }
        return dp[shorter.length()];
    }
}
