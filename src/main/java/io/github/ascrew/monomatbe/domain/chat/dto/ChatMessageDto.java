/*
WebSocket 채팅 메시지 전송에 사용되는 DTO
Redis Pub/Sub을 통해 직렬화/역직렬화되므로 @NoArgsConstructor가 필수적이다.
 */
package io.github.ascrew.monomatbe.domain.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {

    private MessageType type;     // 메시지 유형 (CHAT, ANSWER, ENTER, LEAVE)
    private String roomId;        // 수신 대상 방 코드 (전체 채팅은 "global")
    private String sender;        // 발신자 식별자 (세션에서 추출한 서버 신뢰 값)
    private String content;       // 메시지 본문
    private String timestamp;     // 메시지 발신 시각

    /**
     * 메시지 유형 열거형.
     * - CHAT   : 일반 채팅 메시지
     * - ANSWER : 정답 제출 메시지 (인게임 전용)
     * - ENTER  : 입장 알림 시스템 메시지
     * - LEAVE  : 퇴장 알림 시스템 메시지
     */
    public enum MessageType {
        CHAT, ANSWER, ENTER, LEAVE
    }
}
