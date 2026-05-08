package io.github.ascrew.monomatbe.domain.map.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum MapCategory {
    KPOP("kpop"),
    JPOP("jpop"),
    POP("pop");

    private final String value;

    MapCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MapCategory from(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("category는 null일 수 없습니다.");
        }

        String normalized = rawValue
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");

        return switch (normalized) {
            case "kpop" -> KPOP;
            case "jpop" -> JPOP;
            case "pop" -> POP;
            default -> throw new IllegalArgumentException("지원하지 않는 category입니다: " + rawValue);
        };
    }
}
