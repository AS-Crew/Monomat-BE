package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import org.springframework.data.jpa.domain.Specification;

public class QuizMapSpecification {

    private QuizMapSpecification() {}

    /** is_public=true, is_deleted=false */
    public static Specification<QuizMap> isPublicAndNotDeleted() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("isPublic")),
                cb.isFalse(root.get("isDeleted"))
        );
    }

    /** owner_id=userId, is_deleted=false */
    public static Specification<QuizMap> ownedByAndNotDeleted(Long userId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("owner").get("id"), userId),
                cb.isFalse(root.get("isDeleted"))
        );
    }

    /** title LIKE %keyword% (null/blank → no-op) */
    public static Specification<QuizMap> withKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return (root, query, cb) -> null;
        }

        String pattern = "%" + keyword.toLowerCase() + "%";
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("title")), pattern);
    }

    /**
     * title LIKE %keyword% OR category = parsedKeywordCategory
     *
     * <p>내 맵 목록 검색에서 사용한다.
     * keyword가 "J-POP", "jpop", "애니", "ANIME" 등 카테고리로 해석 가능하면
     * 제목 검색과 카테고리 검색을 함께 수행한다.
     *
     * <p>공개 맵 목록의 기존 검색 결과 변경을 피하기 위해 기존 withKeyword()는 유지한다.
     */
    public static Specification<QuizMap> withKeywordIncludingCategory(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return (root, query, cb) -> null;
        }

        String normalizedKeyword = keyword.trim().toLowerCase();
        String pattern = "%" + normalizedKeyword + "%";

        MapCategory keywordCategory = tryParseCategory(keyword);

        return (root, query, cb) -> {
            var titlePredicate = cb.like(cb.lower(root.get("title")), pattern);

            if (keywordCategory == null) {
                return titlePredicate;
            }

            return cb.or(
                    titlePredicate,
                    cb.equal(root.get("category"), keywordCategory)
            );
        };
    }

    /** category = ? (null → no-op) */
    public static Specification<QuizMap> withCategory(MapCategory category) {
        if (category == null) {
            return (root, query, cb) -> null;
        }

        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    private static MapCategory tryParseCategory(String rawCategory) {
        try {
            return MapCategory.from(rawCategory);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}