package io.github.ascrew.monomatbe.domain.report.dto;

import io.github.ascrew.monomatbe.domain.report.entity.Report;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 신고 생성 응답 DTO
 *
 * [응답 설계]
 * 신고 생성 직후 클라이언트는 상세 운영 정보를 알 필요는 없지만,
 * 중복 제출 방지와 사용자 안내를 위해 생성된 신고 ID와 상태를 반환한다.
 */
@Builder
public record ReportResponse(
        Long reportId,
        Long reporterId,
        Long lobbyId,
        String targetType,
        Long targetId,
        String targetReference,
        String reason,
        String status,
        LocalDateTime createdAt
) {

    /**
     * Report 엔티티를 API 응답 DTO로 변환한다.
     *
     * LAZY 연관관계 접근은 reporter.id, lobby.id 정도로 제한한다.
     * username, lobby title 등 추가 정보는 목록/관리자 API에서 별도 조회하는 편이 안전하다.
     */
    public static ReportResponse from(Report report) {
        return ReportResponse.builder()
                .reportId(report.getId())
                .reporterId(report.getReporter().getId())
                .lobbyId(report.getLobby().getId())
                .targetType(report.getTargetType().name())
                .targetId(report.getTargetId())
                .targetReference(report.getTargetReference())
                .reason(report.getReason())
                .status(report.getStatus().name())
                .createdAt(report.getCreatedAt())
                .build();
    }
}