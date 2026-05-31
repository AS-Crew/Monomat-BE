package io.github.ascrew.monomatbe.domain.report.dto;

import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 관리자 신고 목록 아이템 응답 DTO
 *
 * [목적]
 * 운영자가 신고 목록 화면에서 빠르게 판단할 수 있도록
 * 신고 대상, 신고자, 로비 맥락, 처리 상태, 생성/처리 시각을 제공한다.
 *
 * [주의]
 * 채팅 메시지 원문 같은 상세 정보는 목록 응답에 포함하지 않는다.
 * 상세 조회 API에서 별도 제공한다.
 */
@Builder
public record AdminReportListItemResponse(
        Long reportId,
        Long reporterId,
        String reporterUsername,
        Long lobbyId,
        String lobbyCode,
        String lobbyTitle,
        ReportTargetType targetType,
        Long targetId,
        String targetReference,
        String reason,
        ReportStatus status,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {
}