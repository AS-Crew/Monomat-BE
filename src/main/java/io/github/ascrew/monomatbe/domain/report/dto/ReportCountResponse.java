package io.github.ascrew.monomatbe.domain.report.dto;

import lombok.Builder;

/**
 * 신고 누적 카운트 응답 DTO
 *
 * [사용 목적]
 * - 특정 로비의 누적 신고 수 확인
 * - 특정 신고 대상의 누적 신고 수 확인
 * - 향후 관리자 검토 화면 또는 자동 비공개 정책 판단에 활용
 *
 * 현재 이슈에서는 내부 서비스 로직 위주로 사용하고, 관리자 API가 추가될 때 외부 응답으로 확장할 수 있다.
 */
@Builder
public record ReportCountResponse(
        String targetType,
        Long targetId,
        Long lobbyId,
        String status,
        long count
) {
}