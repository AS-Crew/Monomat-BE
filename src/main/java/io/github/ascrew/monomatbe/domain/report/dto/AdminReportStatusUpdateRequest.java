package io.github.ascrew.monomatbe.domain.report.dto;

import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자 신고 처리 상태 변경 요청 DTO
 *
 * [허용 상태]
 * - RESOLVED  : 유효한 신고로 처리 완료
 * - DISMISSED : 유효하지 않은 신고로 기각
 *
 * [주의]
 * PENDING은 신고 접수 직후의 초기 상태이므로 관리자 처리 요청값으로 허용하지 않는다.
 */
public record AdminReportStatusUpdateRequest(

        @NotNull(message = "변경할 신고 처리 상태는 필수입니다.")
        ReportStatus status
) {
}