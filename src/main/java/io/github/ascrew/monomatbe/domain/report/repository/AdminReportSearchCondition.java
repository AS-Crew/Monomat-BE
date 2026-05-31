package io.github.ascrew.monomatbe.domain.report.repository;

import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;

/**
 * 관리자 신고 목록 조회 검색 조건
 *
 * [확장 목적]
 * 현재는 targetType/status만 사용하지만,
 * 향후 신고자 닉네임, 기간 검색, 키워드 검색 등이 추가되더라도
 * Repository 메서드 조합을 늘리지 않고 단일 동적 쿼리로 처리하기 위한 조건 객체이다.
 */
public record AdminReportSearchCondition(
        ReportTargetType targetType,
        ReportStatus status
) {
}