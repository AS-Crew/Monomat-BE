package io.github.ascrew.monomatbe.domain.chat.service;

/**
 * 로비 최근 채팅 메시지 조회 실패 예외
 *
 * [사용 목적]
 * Redis 장애, timeout, Redis client 오류처럼 서버가 최근 채팅 저장소를 조회할 수 없는 상황을
 * 단순한 "messageId 없음"과 구분하기 위해 사용한다.
 *
 * [상위 처리]
 * LobbyChatMessageReportService는 이 예외를 503 Service Unavailable로 변환한다.
 */
public class RecentChatMessageLookupException extends RuntimeException {

    public RecentChatMessageLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}