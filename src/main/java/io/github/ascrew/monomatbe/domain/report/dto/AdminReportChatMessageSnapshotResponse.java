package io.github.ascrew.monomatbe.domain.report.dto;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 관리자 신고 상세 조회에서 사용하는 로비 채팅 메시지 스냅샷 응답 DTO
 *
 * [목적]
 * Redis 최근 채팅 TTL 만료 후에도 운영자가 신고 당시의 메시지 원문과 발신자 정보를 확인할 수 있도록 한다.
 */
@Builder
public record AdminReportChatMessageSnapshotResponse(
        Long snapshotId,
        String messageId,
        String senderIdentifier,
        Long senderId,
        String senderNickname,
        String content,
        String messageType,
        LocalDateTime sentAt,
        LocalDateTime createdAt
) {
}