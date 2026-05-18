package io.github.ascrew.monomatbe.domain.lobby.dto;

import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * 공개 로비 목록 조회 조건
 *
 * [책임]
 * - 요청 파라미터 정규화
 * - 잘못된 필터/정렬 값 차단
 * - 서비스 계층에 검증된 검색 조건 전달
 */
public record LobbySearchCondition(
        String keyword,
        MapCategory mapCategory,
        LobbySortType sortType
) {

    private static final String ERROR_INVALID_CATEGORY =
            "지원하지 않는 맵 카테고리입니다.";

    public static LobbySearchCondition of(
            String keyword,
            String mapCategory,
            String sort
    ) {
        return new LobbySearchCondition(
                normalizeKeyword(keyword),
                parseMapCategory(mapCategory),
                LobbySortType.from(sort)
        );
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        /*
         * 제목 검색은 대소문자를 구분하지 않는다.
         *
         * 검색어는 요청당 한 번만 생성되는 조건 값이므로,
         * Service에서 로비마다 반복해서 lower-case 변환하지 않도록
         * 조건 객체 생성 시점에 미리 정규화한다.
         */
        return keyword.trim().toLowerCase(Locale.ROOT);
    }

    private static MapCategory parseMapCategory(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return null;
        }

        try {
            return MapCategory.from(rawCategory);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_CATEGORY,
                    e
            );
        }
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isBlank();
    }

    public boolean hasMapCategory() {
        return mapCategory != null;
    }
}