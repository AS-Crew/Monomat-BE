package io.github.ascrew.monomatbe.domain.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로비 내 유저 신고 요청 DTO
 *
 * [신고 대상]
 * 신고 대상 유저 ID는 URL PathVariable로 전달한다.
 * 따라서 요청 body에는 신고 사유만 포함한다.
 *
 * [검증 규칙]
 * - reason: 필수, 공백 불가, 최대 500자
 */
public record LobbyUserReportRequest(

        @NotBlank(message = "신고 사유는 비어 있을 수 없습니다.")
        @Size(max = 500, message = "신고 사유는 500자를 초과할 수 없습니다.")
        String reason
) {
}