package io.github.ascrew.monomatbe.domain.report.repository;

import io.github.ascrew.monomatbe.domain.report.entity.Report;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/**
 * 신고 Repository Custom Query 인터페이스
 *
 * Spring Data JPA 메서드 이름 조합 대신 동적 검색 조건을 처리한다.
 */
public interface ReportRepositoryCustom {

    /**
     * 관리자 신고 목록을 동적 조건으로 조회한다.
     *
     * @param condition 검색 조건
     * @param pageable  페이징 정보
     * @return count query 없는 Slice 결과
     */
    Slice<Report> searchAdminReports(
            AdminReportSearchCondition condition,
            Pageable pageable
    );
}