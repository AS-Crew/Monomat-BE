package io.github.ascrew.monomatbe.domain.report.repository;

import io.github.ascrew.monomatbe.domain.report.entity.Report;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 신고 Repository Custom Query 구현체.
 *
 * [설계 이유]
 * 관리자 신고 목록 조회는 현재 targetType/status만 사용하지만,
 * 운영자 검색 기능은 신고자 닉네임, 기간, 키워드 등으로 확장될 가능성이 높다.
 *
 * Spring Data JPA 메서드 이름 조합 방식은 필터가 늘어날수록
 * 메서드 수가 기하급수적으로 증가하므로 Criteria API 기반 동적 쿼리로 처리한다.
 *
 * [페이징 정책]
 * 응답 DTO는 totalCount/totalPages를 사용하지 않고 hasNext만 사용한다.
 * 따라서 count query가 발생하는 Page 대신 limit + 1 방식의 Slice를 반환한다.
 */
@Repository
public class ReportRepositoryImpl implements ReportRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Slice<Report> searchAdminReports(
            AdminReportSearchCondition condition,
            Pageable pageable
    ) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Report> criteriaQuery = criteriaBuilder.createQuery(Report.class);
        Root<Report> report = criteriaQuery.from(Report.class);

        report.fetch("reporter", JoinType.INNER);
        report.fetch("lobby", JoinType.INNER);

        List<Predicate> predicates = buildPredicates(
                condition,
                criteriaBuilder,
                report
        );

        criteriaQuery.select(report)
                .where(predicates.toArray(Predicate[]::new))
                .orderBy(criteriaBuilder.desc(report.get("createdAt")))
                .distinct(true);

        TypedQuery<Report> query = entityManager.createQuery(criteriaQuery);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize() + 1);

        List<Report> results = query.getResultList();

        boolean hasNext = results.size() > pageable.getPageSize();

        if (hasNext) {
            results = results.subList(0, pageable.getPageSize());
        }

        return new SliceImpl<>(
                results,
                pageable,
                hasNext
        );
    }

    private List<Predicate> buildPredicates(
            AdminReportSearchCondition condition,
            CriteriaBuilder criteriaBuilder,
            Root<Report> report
    ) {
        List<Predicate> predicates = new ArrayList<>();

        if (condition.targetType() != null) {
            predicates.add(criteriaBuilder.equal(
                    report.get("targetType"),
                    condition.targetType()
            ));
        }

        if (condition.status() != null) {
            predicates.add(criteriaBuilder.equal(
                    report.get("status"),
                    condition.status()
            ));
        }

        return predicates;
    }
}