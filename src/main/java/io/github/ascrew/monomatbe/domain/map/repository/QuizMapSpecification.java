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

    /** title LIKE %keyword% (null/blank → null → no-op) */
    public static Specification<QuizMap> withKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String pattern = "%" + keyword + "%";
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("title")), pattern);
    }

    /** category = ? (null → null → no-op) */
    public static Specification<QuizMap> withCategory(MapCategory category) {
        if (category == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }
}
