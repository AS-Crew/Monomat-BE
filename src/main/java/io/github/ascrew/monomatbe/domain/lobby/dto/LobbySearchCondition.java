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
 * - 잘못된 필터/정렬/페이징 값 차단
 * - 서비스 계층에 검증된 검색 조건 전달
 */
public record LobbySearchCondition(
        String keyword,
        MapCategory mapCategory,
        LobbySortType sortType,
        LobbyPageRequest pageRequest
) {

    private static final String ERROR_INVALID_CATEGORY =
            "지원하지 않는 맵 카테고리입니다.";

    /**
     * 기존 테스트/내부 호출 호환용 팩토리 메서드
     *
     * [유지 이유]
     * 기존 Service 단위 테스트는 keyword/mapCategory/sort만 검증한다.
     * 페이징이 필요하지 않은 테스트까지 모두 수정하면 변경 범위가 불필요하게 커진다.
     *
     * 기본 페이징 정책은 LobbyPageRequest에 위임한다.
     */
    public static LobbySearchCondition of(
            String keyword,
            String mapCategory,
            String sort
    ) {
        return of(
                keyword,
                mapCategory,
                sort,
                null,
                null
        );
    }

    /**
     * 공개 로비 목록 조회 조건 생성
     *
     * @param keyword     로비 제목 검색어
     * @param mapCategory 맵 카테고리 필터
     * @param sort        정렬 기준
     * @param page        0-based 페이지 번호
     * @param size        페이지 크기
     * @return 정규화/검증된 조회 조건
     */
    public static LobbySearchCondition of(
            String keyword,
            String mapCategory,
            String sort,
            Integer page,
            Integer size
    ) {
        return new LobbySearchCondition(
                normalizeKeyword(keyword),
                parseMapCategory(mapCategory),
                LobbySortType.from(sort),
                LobbyPageRequest.of(page, size)
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