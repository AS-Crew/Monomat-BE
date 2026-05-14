package io.github.ascrew.monomatbe.domain.map.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 퀴즈 맵 카테고리.
 *
 * [역할]
 * - DB에는 EnumType.STRING 정책에 따라 KPOP, JPOP, POP 형태로 저장된다.
 * - HTTP JSON 응답에는 FE 필터 값과 맞춘 표시 값(K-POP, J-POP, POP)을 내려준다.
 *
 * [입력 정책]
 * 요청 값은 하이픈, 언더스코어, 공백, 대소문자 차이를 허용한다.
 * 예: "jpop", "JPOP", "J-POP", "J_POP", "J POP" 모두 JPOP으로 정규화한다.
 */
public enum MapCategory {

    KPOP("K-POP"),
    JPOP("J-POP"),
    POP("POP");

    private final String displayValue;

    MapCategory(String displayValue) {
        this.displayValue = displayValue;
    }

    /**
     * JSON 응답에 사용할 카테고리 표시 값을 반환한다.
     *
     * FE 필터 값과 응답 값을 일치시키기 위해 내부 enum 이름이 아니라 displayValue를 직렬화한다.
     */
    @JsonValue
    public String value() {
        return displayValue;
    }

    /**
     * 요청 또는 저장소에서 들어온 카테고리 문자열을 enum으로 변환한다.
     *
     * @param rawValue 원본 카테고리 값
     * @return 정규화된 MapCategory
     */
    @JsonCreator
    public static MapCategory from(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("category는 null일 수 없습니다.");
        }

        String normalized = normalize(rawValue);

        return switch (normalized) {
            case "kpop" -> KPOP;
            case "jpop" -> JPOP;
            case "pop" -> POP;
            default -> throw new IllegalArgumentException("지원하지 않는 category입니다: " + rawValue);
        };
    }

    /**
     * 외부 저장소 또는 요청 값으로 들어온 카테고리를 응답 표시 값으로 변환한다.
     *
     * Redis에 과거 값("jpop")이 남아 있어도 FE 응답 계약("J-POP")을 유지하기 위해 사용한다.
     *
     * @param rawValue 원본 카테고리 값
     * @return FE 응답에 사용할 표시 값
     */
    public static String toDisplayValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        return from(rawValue).value();
    }

    /**
     * 카테고리 입력값을 비교 가능한 내부 문자열로 정규화한다.
     *
     * 예:
     * - "J-POP" -> "jpop"
     * - "J_POP" -> "jpop"
     * - "J POP" -> "jpop"
     */
    private static String normalize(String rawValue) {
        return rawValue
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }
}