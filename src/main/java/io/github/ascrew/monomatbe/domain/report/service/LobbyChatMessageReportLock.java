package io.github.ascrew.monomatbe.domain.report.service;

/**
 * 로비 채팅 메시지 신고 lock 소유권 정보
 *
 * [사용 목적]
 * Redis lock 해제 시 현재 요청이 획득한 lock인지 확인하기 위한 token을 보관한다.
 */
public record LobbyChatMessageReportLock(
        String key,
        String token,
        Long reporterId,
        Long lobbyId,
        String messageId
) {
}