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
     * - 3~5글자: 임계 거리 1 허용
     * - 6~9글자: 임계 거리 2 허용
     * - 10글자 이상: 임계 거리 3 허용
     *
     * @param targetLength 정규화된 정답 후보 문자열의 길이
     * @return 허용할 수 있는 최대 Levenshtein Distance
     */
    public static int getThreshold(int targetLength) {
        if (targetLength <= 2) {
            return 0;
        } else if (targetLength <= 5) {
            return 1;
        } else if (targetLength <= 9) {
            return 2;
        } else {
            return 3;
        }
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

        int targetLength = normalizedTarget.length();
        int threshold = getThreshold(targetLength);
        if (threshold == 0) {
            return false;
        }

        // 사용자가 적은 답안이 타겟보다 너무 길거나 짧으면 Levenshtein 연산 필요 없이 탈락시킬 수 있는 조기 최적화
        if (Math.abs(normalizedAnswer.length() - targetLength) > threshold) {
            return false;
        }

        int distance = LevenshteinDistance.calculate(normalizedAnswer, normalizedTarget);
        return distance <= threshold;
    }
}
