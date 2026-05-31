package io.github.ascrew.monomatbe.domain.game.support;

/**
 * 정답 글자 수 비례 오타 허용 임계 거리 판단 및 매칭을 수용하는 매처 유틸리티.
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {}

    /**
     * 정답 후보의 글자 수에 따른 오타 허용 여부와 최대 임계 거리를 반환합니다.
     *
     * [정책]
     * - 1~2글자: 오타 비허용 (임계 거리 0)
     * - 5글자 이하이면서 ASCII 영문/숫자로만 이루어진 정답: 오타 비허용 (임계 거리 0)
     * - 3~5글자 (그 외): 임계 거리 1 허용
     * - 6~9글자: 임계 거리 2 허용
     * - 10글자 이상: 임계 거리 3 허용
     *
     * @param normalizedTarget 정규화된 정답 후보 문자열
     * @return 허용할 수 있는 최대 Levenshtein Distance
     */
    public static int getThreshold(String normalizedTarget) {
        int length = normalizedTarget.length();

        if (length <= 2) {
            return 0;
        }

        if (isAsciiAlphaNumeric(normalizedTarget) && length <= 5) {
            return 0;
        }

        if (length <= 5) {
            return 1;
        }

        if (length <= 9) {
            return 2;
        }

        return 3;
    }

    private static boolean isAsciiAlphaNumeric(String value) {
        return value.chars().allMatch(ch ->
                (ch >= 'a' && ch <= 'z') ||
                (ch >= '0' && ch <= '9')
        );
    }

    /**
     * 사용자가 제출한 답안이 정답 후보와 오타 매칭을 포함해 만족하는지 판단합니다.
     *
     * @param normalizedAnswer 사용자가 제출한 정규화 완료된 답안 (공백 제거, 소문자화 됨)
     * @param normalizedTarget 정답 후보군 중 정규화 완료된 정답 (공백 제거, 소문자화 됨)
     * @return 정답 여부
     */
    public static boolean isMatch(String normalizedAnswer, String normalizedTarget) {
        if (normalizedAnswer == null || normalizedTarget == null) {
            return false;
        }
        if (normalizedAnswer.equals(normalizedTarget)) {
            return true;
        }

        int threshold = getThreshold(normalizedTarget);
        if (threshold == 0) {
            return false;
        }

        int targetLength = normalizedTarget.length();
        // 사용자가 적은 답안이 타겟보다 너무 길거나 짧으면 Levenshtein 연산 필요 없이 탈락시킬 수 있는 조기 최적화
        if (Math.abs(normalizedAnswer.length() - targetLength) > threshold) {
            return false;
        }

        int distance = LevenshteinDistance.calculate(normalizedAnswer, normalizedTarget);
        return distance <= threshold;
    }
}
