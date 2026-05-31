package io.github.ascrew.monomatbe.domain.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로비 채팅 메시지 신고 요청 DTO
 *
 * [신고 대상]
 * 신고 대상 채팅 메시지 ID는 URL PathVariable로 전달한다.
 * 따라서 요청 body에는 신고 사유만 포함한다.
 *
 * [검증 규칙]
 * - reason: 필수, 공백 불가, 최대 500자
 *
 * [정규화 책임]
 * DTO는 입력 형식 검증만 담당한다.
 * trim 처리는 서비스 레이어에서 수행해 저장값을 정규화한다.
 */
public record LobbyChatMessageReportRequest(

        @NotBlank(message = "신고 사유는 비어 있을 수 없습니다.")
        @Size(max = 500, message = "신고 사유는 500자를 초과할 수 없습니다.")
        String reason
) {
}