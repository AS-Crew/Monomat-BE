package io.github.ascrew.monomatbe.domain.report.dto;

import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 관리자 신고 상세 조회 응답 DTO
 *
 * [목적]
 * 운영자가 신고 하나를 처리하기 전에 신고자, 신고 대상, 로비 맥락,
 * 신고 사유, 처리 상태, 채팅 메시지 스냅샷을 확인할 수 있도록 한다.
 */
@Builder
public record AdminReportDetailResponse(
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
        LocalDateTime resolvedAt,

        AdminReportChatMessageSnapshotResponse chatMessageSnapshot
) {
}