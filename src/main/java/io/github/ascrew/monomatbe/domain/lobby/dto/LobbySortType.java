package io.github.ascrew.monomatbe.domain.lobby.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 공개 로비 목록 정렬 기준
 *
 * [요청 값]
 * - latest
 * - most_players
 * - most_available
 *
 * [정책]
 * 문자열 파라미터를 서비스까지 그대로 전달하지 않고,
 * enum으로 정규화하여 정렬 정책을 명확하게 제한한다.
 */
public enum LobbySortType {

    LATEST("latest"),
    MOST_PLAYERS("most_players"),
    MOST_AVAILABLE("most_available");

    private static final String ERROR_INVALID_SORT_FORMAT =
            "지원하지 않는 로비 정렬 기준입니다. 사용 가능한 값: %s";

    private final String requestValue;

    LobbySortType(String requestValue) {
        this.requestValue = requestValue;
    }

    public String requestValue() {
        return requestValue;
    }

    public static LobbySortType from(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return LATEST;
        }

        String normalized = normalize(rawValue);

        return Arrays.stream(values())
                .filter(sortType -> sortType.requestValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        ERROR_INVALID_SORT_FORMAT.formatted(allowedValues())
                ));
    }

    private static String normalize(String rawValue) {
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    private static String allowedValues() {
        return Arrays.stream(values())
                .map(LobbySortType::requestValue)
                .collect(Collectors.joining(", "));
    }
}